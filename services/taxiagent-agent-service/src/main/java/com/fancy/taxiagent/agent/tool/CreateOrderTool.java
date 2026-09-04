package com.fancy.taxiagent.agent.tool;

import com.fancy.taxiagent.agent.client.OrderServiceFeignClient;
import com.fancy.taxiagent.agent.client.dto.CreateOrderRequest;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.state.OrderStateService;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import feign.FeignException;
import feign.RetryableException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 创建订单工具：用户确认后调 order-service 落库。
 *
 * <p>若估算快照（OrderEstimateTool 存入的 est* 字段）与当前槽位一致（车型/加急/起终点坐标），
 * 透传 {@code mongoTraceId}/{@code estPrice}/{@code estDistance}/{@code radio}，
 * order-service 跳过二次算路；否则传 null 由其重新规划（见 OrderServiceImpl.createOrder）。
 * 下单成功记录 orderId 并清空槽位。</p>
 *
 * <p>上游 4xx 拒绝映射为 {@code ORDER_REQUEST_REJECTED}，5xx 与可重试失败映射为
 * {@code ORDER_UNAVAILABLE}（照 OrderEstimateTool 错误映射）；其余失败返回
 * "Tool execution failed: " 开头的说明。</p>
 */
@Service
public class CreateOrderTool implements AgentTool {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final OrderStateService stateService;
    private final OrderServiceFeignClient orderServiceFeignClient;

    public CreateOrderTool(OrderStateService stateService, OrderServiceFeignClient orderServiceFeignClient) {
        this.stateService = stateService;
        this.orderServiceFeignClient = orderServiceFeignClient;
    }

    @Override
    public String name() {
        return "createOrder";
    }

    @Override
    public String description() {
        return "创建订单：仅在用户确认订单摘要后调用。调用前必须先执行 confirmOrder 得到用户确认。";
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
            return "用户尚未确认订单，请先请用户确认后再创建。";
        }
        Map<String, String> slots = stateService.getAllSlots(conversationId);
        Map<String, String> estimate = stateService.getEstimate(conversationId);
        String missing = missingRequired(slots);
        if (missing != null) {
            return "还缺少订单参数: " + missing + "，请补充后重试。";
        }
        if (slots.get("scheduledTime") != null) {
            String rejection = ScheduledTimePolicy.rejectIfInvalid(slots.get("scheduledTime"), LocalDateTime.now());
            if (rejection != null) {
                return rejection;
            }
        }
        try {
            ReusedEstimate reused = reusableEstimate(slots, estimate);
            CreateOrderRequest request = new CreateOrderRequest(
                    reused == null ? null : reused.traceId(),
                    Integer.valueOf(slots.get("vehicleType")),
                    Integer.valueOf(slots.get("isReservation")),
                    Integer.valueOf(slots.getOrDefault("isExpedited", "0")),
                    slots.get("scheduledTime") == null ? null
                            : LocalDateTime.parse(slots.get("scheduledTime"), TIME_FORMAT),
                    slots.get("startAddress"),
                    decimal(slots.get("startLat")), decimal(slots.get("startLng")),
                    slots.get("endAddress"),
                    decimal(slots.get("endLat")), decimal(slots.get("endLng")),
                    reused == null ? null : reused.estPrice(),
                    reused == null ? null : reused.estDistance(),
                    reused == null ? null : reused.radio());
            String orderId = orderServiceFeignClient.createOrder(
                    context.authorization(), context.traceId(), request);
            stateService.setOrderId(conversationId, orderId);
            stateService.deleteSlots(conversationId);
            return "订单创建成功，订单号 " + orderId + "。如需下新单请创建新对话。";
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
     * 计算缺失的必填槽位（scheduledTime 仅预约单必填，与 confirmOrder 口径一致）。
     *
     * @param slots 当前槽位
     * @return 缺失字段提示；齐备返回 null
     */
    private String missingRequired(Map<String, String> slots) {
        boolean reservation = "1".equals(slots.get("isReservation"));
        StringBuilder missing = new StringBuilder();
        appendIfMissing(missing, slots, "vehicleType");
        appendIfMissing(missing, slots, "isReservation");
        appendIfMissing(missing, slots, "isExpedited");
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

    /**
     * 估算快照能否复用：四要素齐全，且车型/加急/起终点坐标与当前槽位一致
     * （数值按 {@link BigDecimal#compareTo} 比较，兼容小数位写法差异）。
     * 不一致（改目的地、换车型后未重新估算等）返回 null，由 order-service 重新规划兜底。
     */
    private ReusedEstimate reusableEstimate(Map<String, String> slots, Map<String, String> estimate) {
        String traceId = estimate.get("estTraceId");
        String estDistance = estimate.get("estDistance");
        String estPrice = estimate.get("estPrice");
        String estRadio = estimate.get("estRadio");
        if (traceId == null || estDistance == null || estPrice == null || estRadio == null) {
            return null;
        }
        String slotVehicleType = slots.get("vehicleType");
        String slotExpedited = normalizeExpedited(slots.get("isExpedited"));
        if (!numberEquals(slotVehicleType, estimate.get("estVehicleType"))
                || !numberEquals(slotExpedited, normalizeExpedited(estimate.get("estIsExpedited")))) {
            return null;
        }
        if (!numberEquals(slots.get("startLat"), estimate.get("estStartLat"))
                || !numberEquals(slots.get("startLng"), estimate.get("estStartLng"))
                || !numberEquals(slots.get("endLat"), estimate.get("estEndLat"))
                || !numberEquals(slots.get("endLng"), estimate.get("estEndLng"))) {
            return null;
        }
        return new ReusedEstimate(traceId, new BigDecimal(estPrice), new BigDecimal(estDistance),
                new BigDecimal(estRadio));
    }

    private boolean numberEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        try {
            return new BigDecimal(left).compareTo(new BigDecimal(right)) == 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private String normalizeExpedited(String value) {
        return value == null || value.isBlank() ? "0" : value.trim();
    }

    /** 可复用的估算结果（透传给 order-service 跳过二次算路）。 */
    private record ReusedEstimate(String traceId, BigDecimal estPrice, BigDecimal estDistance, BigDecimal radio) {
    }

    private AgentExecutionException unavailable(Throwable cause) {
        return new AgentExecutionException("ORDER_UNAVAILABLE", "订单服务暂不可用", cause);
    }

    /** 创建订单工具无参参数。 */
    public record Arguments() {
    }
}
