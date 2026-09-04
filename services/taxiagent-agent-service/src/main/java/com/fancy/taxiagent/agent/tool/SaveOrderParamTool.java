package com.fancy.taxiagent.agent.tool;

import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.state.OrderStateService;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 保存订单槽位工具：一次写入一个或多个合法字段（车型/预约/加急/时间/起终点）。
 *
 * <p>非法字段或非法值返回 "Tool execution failed: " 开头的拒绝文本（不抛异常），
 * 模型可据此修正参数后重试；已下单的对话拒绝再次保存。</p>
 */
@Service
public class SaveOrderParamTool implements AgentTool {

    private static final Set<String> VALID_FIELDS = Set.of(
            "vehicleType", "isReservation", "isExpedited", "scheduledTime",
            "startAddress", "startLat", "startLng", "endAddress", "endLat", "endLng");

    private final OrderStateService stateService;
    private final ObjectMapper objectMapper;

    public SaveOrderParamTool(OrderStateService stateService, ObjectMapper objectMapper) {
        this.stateService = stateService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "saveOrderParam";
    }

    @Override
    public String description() {
        return "保存订单参数槽位。参数为 JSON 对象，可一次填写一个或多个字段："
                + "vehicleType（1 快车/2 优享/3 专车）、isReservation（0/1 是否预约）、"
                + "isExpedited（0/1 是否加急）、scheduledTime（预约时间 yyyy-MM-dd HH:mm，仅预约单必填）、"
                + "startAddress/startLat/startLng/endAddress/endLat/endLng（起终点地址与经纬度）。";
    }

    @Override
    public Class<?> inputType() {
        return Arguments.class;
    }

    @Override
    public String execute(String jsonArgs, AgentExecutionContext context, AgentStreamSink sink) {
        if (context == null) {
            return "Tool execution failed: 缺少执行上下文";
        }
        String conversationId = context.conversationId();
        if (stateService.isOrderCreated(conversationId)) {
            return "订单已创建，如需下新单请创建新对话。";
        }
        try {
            Arguments arguments = objectMapper.readValue(jsonArgs, Arguments.class);
            List<String> saved = new ArrayList<>();
            for (String field : VALID_FIELDS) {
                String value = valueOf(arguments, field);
                if (value == null || value.isBlank()) {
                    continue;
                }
                String error = validate(field, value);
                if (error != null) {
                    return error;
                }
                stateService.setSlot(conversationId, field, value);
                saved.add(field + "=" + value);
            }
            if (saved.isEmpty()) {
                return "Tool execution failed: 参数必须包含至少一个有效字段";
            }
            return "已保存订单参数: " + String.join(", ", saved);
        } catch (Exception exception) {
            return "Tool execution failed: " + exception.getMessage();
        }
    }

    /**
     * 从参数 record 中按字段名取值。
     *
     * @param arguments 模型参数
     * @param field 字段名
     * @return 字段值；未填写返回 null
     */
    private String valueOf(Arguments arguments, String field) {
        return switch (field) {
            case "vehicleType" -> arguments.vehicleType();
            case "isReservation" -> arguments.isReservation();
            case "isExpedited" -> arguments.isExpedited();
            case "scheduledTime" -> arguments.scheduledTime();
            case "startAddress" -> arguments.startAddress();
            case "startLat" -> arguments.startLat();
            case "startLng" -> arguments.startLng();
            case "endAddress" -> arguments.endAddress();
            case "endLat" -> arguments.endLat();
            case "endLng" -> arguments.endLng();
            default -> null;
        };
    }

    /**
     * 校验单个字段值；合法返回 null，非法返回拒绝文本。
     *
     * @param field 字段名
     * @param value 字段值
     * @return 拒绝文本；合法返回 null
     */
    private String validate(String field, String value) {
        return switch (field) {
            case "vehicleType" -> Set.of("1", "2", "3").contains(value) ? null
                    : "Tool execution failed: 车型必须是 1（快车）/2（优享）/3（专车）";
            case "isReservation", "isExpedited" -> Set.of("0", "1").contains(value) ? null
                    : "Tool execution failed: " + field + " 必须是 0 或 1";
            case "scheduledTime" -> ScheduledTimePolicy.rejectIfInvalid(value, LocalDateTime.now());
            case "startLat", "startLng", "endLat", "endLng" -> {
                try {
                    BigDecimal coordinate = new BigDecimal(value);
                    if (coordinate.abs().compareTo(BigDecimal.valueOf(180)) > 0) {
                        yield "Tool execution failed: " + field + " 超出有效范围";
                    }
                    yield null;
                } catch (NumberFormatException e) {
                    yield "Tool execution failed: " + field + " 必须是数字";
                }
            }
            default -> null;
        };
    }

    /**
     * 保存订单参数工具参数：可填写任意字段组合，未填字段忽略。
     *
     * @param vehicleType 车型（1 快车/2 优享/3 专车）
     * @param isReservation 是否预约（0/1）
     * @param isExpedited 是否加急（0/1）
     * @param scheduledTime 预约时间（yyyy-MM-dd HH:mm，仅预约单必填）
     * @param startAddress 起点地址
     * @param startLat 起点纬度
     * @param startLng 起点经度
     * @param endAddress 终点地址
     * @param endLat 终点纬度
     * @param endLng 终点经度
     */
    public record Arguments(
            String vehicleType,
            String isReservation,
            String isExpedited,
            String scheduledTime,
            String startAddress,
            String startLat,
            String startLng,
            String endAddress,
            String endLat,
            String endLng
    ) {
    }
}
