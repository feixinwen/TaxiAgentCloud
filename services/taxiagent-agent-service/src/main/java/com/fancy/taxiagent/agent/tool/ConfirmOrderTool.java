package com.fancy.taxiagent.agent.tool;

import com.fancy.taxiagent.agent.client.OrderServiceFeignClient;
import com.fancy.taxiagent.agent.client.dto.OrderEstimateRequest;
import com.fancy.taxiagent.agent.client.dto.PriceEstimateView;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.state.OrderStateService;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.RetryableException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单确认工具：槽位齐备后调 estimate 算价，发 confirm 事件（HITL 暂停）等待用户确认。
 *
 * <p>车型/预约/加急未指定时按默认值（快车/立即出发/不加急）兜底补齐并持久化，
 * 必填项只剩起终点（预约单另需 scheduledTime）；补齐情况写回工具结果供模型告知用户。</p>
 *
 * <p>估算结果（traceId/estDistance/estPrice 等）随确认刷新到订单状态快照——这是用户确认的
 * 价格口径，CreateOrderTool 校验槽位一致后透传，order-service 跳过二次算路。</p>
 *
 * <p>工具执行拿不到当前模型 toolCallId，因此用固定哨兵值 {@code "confirmOrder"} 写入
 * {@code lastConfirmToolCallId}；resume 流程按哨兵名注入用户确认反馈
 * （ModelToolResult 的 toolCallId 与历史 assistant 工具调用 id 不做交叉校验，仅作容器）。</p>
 *
 * <p>上游 4xx 拒绝映射为 {@code ORDER_REQUEST_REJECTED}，5xx 与可重试失败映射为
 * {@code ORDER_UNAVAILABLE}（照 OrderEstimateTool 错误映射）；其余失败返回
 * "Tool execution failed: " 开头的说明。</p>
 */
@Service
public class ConfirmOrderTool implements AgentTool {

    /** confirm 事件对应的哨兵工具调用 ID（resume 注入点，见类说明）。 */
    private static final String SENTINEL_TOOL_CALL_ID = "confirmOrder";

    /** 用户未指定时的槽位默认值（快车/立即出发/不加急），confirmOrder 兜底补齐；LinkedHashMap 固定补齐顺序。 */
    private static final Map<String, String> DEFAULT_SLOTS = new LinkedHashMap<>() {
        {
            put("vehicleType", "1");
            put("isReservation", "0");
            put("isExpedited", "0");
        }
    };

    private final OrderStateService stateService;
    private final OrderServiceFeignClient orderServiceFeignClient;
    private final ObjectMapper objectMapper;

    public ConfirmOrderTool(OrderStateService stateService,
                            OrderServiceFeignClient orderServiceFeignClient,
                            ObjectMapper objectMapper) {
        this.stateService = stateService;
        this.orderServiceFeignClient = orderServiceFeignClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "confirmOrder";
    }

    @Override
    public String description() {
        return "确认订单：校验起终点已收集完整（车型/预约/加急缺省时自动按默认值快车/立即出发/不加急补齐），"
                + "估算价格并生成订单摘要请用户确认。确认后请用户回复确认或修改。";
    }

    @Override
    public Class<?> inputType() {
        return Arguments.class;
    }

    @Override
    public String execute(String jsonArgs, AgentExecutionContext context, AgentStreamSink sink) {
        String conversationId = context.conversationId();
        if (stateService.isOrderCreated(conversationId)) {
            return "订单已创建，如需下新单请创建新对话。";
        }
        if (stateService.isBreak(conversationId)) {
            return "正在等待您的确认，请回复确认下单或说明修改内容。";
        }
        Map<String, String> slots = new HashMap<>(stateService.getAllSlots(conversationId));
        List<String> defaultsApplied = applyDefaults(conversationId, slots);
        String missing = missingRequired(slots);
        if (missing != null) {
            return "还缺少订单参数: " + missing + "，请补充后重试。";
        }
        try {
            PriceEstimateView view = orderServiceFeignClient.estimate(
                    context.authorization(),
                    context.traceId(),
                    new OrderEstimateRequest(
                            decimal(slots.get("startLat")), decimal(slots.get("startLng")),
                            decimal(slots.get("endLat")), decimal(slots.get("endLng")),
                            Integer.valueOf(slots.get("vehicleType")),
                            Integer.valueOf(slots.getOrDefault("isExpedited", "0"))));
            String summary = buildSummary(slots, view);
            saveEstimateSnapshot(conversationId, slots, view);
            sink.confirm(summary, buildRouteData(slots, view));
            stateService.setLastConfirmToolCallId(conversationId, SENTINEL_TOOL_CALL_ID);
            stateService.setBreak(conversationId, true);
            String prefix = defaultsApplied.isEmpty() ? ""
                    : "已按默认值补齐（" + defaultLabels(slots, defaultsApplied) + "），用户可在确认时修改。";
            return prefix + "订单摘要已发送，请用户确认：" + summary;
        } catch (RetryableException exception) {
            throw unavailable(exception);
        } catch (FeignException exception) {
            if (exception.status() >= 400 && exception.status() < 500) {
                throw new AgentExecutionException("ORDER_REQUEST_REJECTED", "订单请求被拒绝", exception);
            }
            throw unavailable(exception);
        } catch (Exception exception) {
            return "Tool execution failed: " + exception.getMessage();
        }
    }

    /**
     * 将缺失的默认槽位（车型/预约/加急）兜底补齐并持久化。
     *
     * @param conversationId 会话 ID
     * @param slots 当前槽位（就地补齐）
     * @return 本次补齐的字段列表（空表示全部已由用户指定）
     */
    private List<String> applyDefaults(String conversationId, Map<String, String> slots) {
        List<String> applied = new ArrayList<>();
        for (Map.Entry<String, String> entry : DEFAULT_SLOTS.entrySet()) {
            String field = entry.getKey();
            String current = slots.get(field);
            if (current == null || current.isBlank()) {
                stateService.setSlot(conversationId, field, entry.getValue());
                slots.put(field, entry.getValue());
                applied.add(field);
            }
        }
        return applied;
    }

    private String defaultLabels(Map<String, String> slots, List<String> fields) {
        List<String> labels = new ArrayList<>();
        for (String field : fields) {
            labels.add(switch (field) {
                case "vehicleType" -> "车型=" + vehicleTypeName(slots.get(field));
                case "isReservation" -> "0".equals(slots.get(field)) ? "立即出发" : "预约单";
                case "isExpedited" -> "0".equals(slots.get(field)) ? "不加急" : "加急";
                default -> field;
            });
        }
        return String.join("、", labels);
    }

    /**
     * 计算缺失的必填槽位（scheduledTime 仅预约单必填）。
     *
     * @param slots 当前槽位
     * @return 缺失字段提示；齐备返回 null
     */
    private String missingRequired(Map<String, String> slots) {
        boolean reservation = "1".equals(slots.get("isReservation"));
        StringBuilder missing = new StringBuilder();
        if (reservation) {
            appendIfMissing(missing, slots, "scheduledTime");
        }
        appendIfMissing(missing, slots, "startAddress");
        appendIfMissing(missing, slots, "startLat");
        appendIfMissing(missing, slots, "startLng");
        appendIfMissing(missing, slots, "endAddress");
        appendIfMissing(missing, slots, "endLat");
        appendIfMissing(missing, slots, "endLng");
        return missing.length() == 0 ? null : missing.toString();
    }

    private void appendIfMissing(StringBuilder missing, Map<String, String> slots, String field) {
        String value = slots.get(field);
        if (value == null || value.isBlank()) {
            if (missing.length() > 0) {
                missing.append(", ");
            }
            missing.append(field);
        }
    }

    private BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    /** 确认阶段估算即用户确认的价格口径，刷新快照供 createOrder 复用轨迹。 */
    private void saveEstimateSnapshot(String conversationId, Map<String, String> slots, PriceEstimateView view) {
        Map<String, String> snapshot = new HashMap<>();
        snapshot.put("estTraceId", view.traceId());
        snapshot.put("estDistance", plain(view.estDistance()));
        snapshot.put("estPrice", plain(view.estPrice()));
        snapshot.put("estRadio", plain(view.estPriceRadio()));
        snapshot.put("estVehicleType", slots.get("vehicleType"));
        snapshot.put("estIsExpedited", slots.getOrDefault("isExpedited", "0"));
        snapshot.put("estStartLat", slots.get("startLat"));
        snapshot.put("estStartLng", slots.get("startLng"));
        snapshot.put("estEndLat", slots.get("endLat"));
        snapshot.put("estEndLng", slots.get("endLng"));
        stateService.setEstimate(conversationId, snapshot);
    }

    private String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /**
     * 构建 confirm 事件携带的路线数据（供前端在地图上画估价路线）。
     *
     * <p>字段平铺：traceId/estDistance/startLat/startLng/endLat/endLng。
     * 坐标转 Double 便于前端直接构造高德 LngLat；estDistance 单位 km。
     * traceId 缺失（理论上不会发生：estimate 成功必有）时返回空 Map，
     * 事件退化为无路线字段的旧契约。</p>
     *
     * @param slots 已保存的槽位
     * @param view 价格估算结果
     * @return 路线数据（不可空）
     */
    private Map<String, Object> buildRouteData(Map<String, String> slots, PriceEstimateView view) {
        Map<String, Object> route = new LinkedHashMap<>();
        if (view.traceId() == null || view.traceId().isBlank()) {
            return route;
        }
        route.put("traceId", view.traceId());
        route.put("estDistance", view.estDistance());
        route.put("startLat", coordinate(slots.get("startLat")));
        route.put("startLng", coordinate(slots.get("startLng")));
        route.put("endLat", coordinate(slots.get("endLat")));
        route.put("endLng", coordinate(slots.get("endLng")));
        return route;
    }

    private Double coordinate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * 构建订单摘要 JSON（不包含鉴权信息，仅展示给用户确认的价格与行程要素）。
     *
     * @param slots 已保存的槽位
     * @param view 价格估算结果
     * @return 摘要 JSON 文本
     */
    private String buildSummary(Map<String, String> slots, PriceEstimateView view) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("startAddress", slots.get("startAddress"));
        summary.put("endAddress", slots.get("endAddress"));
        summary.put("vehicleType", vehicleTypeName(slots.get("vehicleType")));
        summary.put("isReservation", "1".equals(slots.get("isReservation")));
        summary.put("isExpedited", "1".equals(slots.get("isExpedited")));
        if (slots.get("scheduledTime") != null) {
            summary.put("scheduledTime", slots.get("scheduledTime"));
        }
        summary.put("estPrice", view.estPrice());
        summary.put("estPriceBase", view.estPriceBase());
        summary.put("estPriceTime", view.estPriceTime());
        summary.put("estPriceDistance", view.estPriceDistance());
        summary.put("estPriceExpedited", view.estPriceExpedited());
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (Exception exception) {
            return "{\"startAddress\":\"" + slots.get("startAddress") + "\",\"endAddress\":\""
                    + slots.get("endAddress") + "\"}";
        }
    }

    private String vehicleTypeName(String value) {
        return switch (value) {
            case "1" -> "快车";
            case "2" -> "优享";
            case "3" -> "专车";
            default -> value;
        };
    }

    private AgentExecutionException unavailable(Throwable cause) {
        return new AgentExecutionException("ORDER_UNAVAILABLE", "订单服务暂不可用", cause);
    }

    /** 订单确认工具无参参数。 */
    public record Arguments() {
    }
}
