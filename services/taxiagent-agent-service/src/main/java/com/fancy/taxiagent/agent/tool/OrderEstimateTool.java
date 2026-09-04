package com.fancy.taxiagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.agent.client.OrderServiceFeignClient;
import com.fancy.taxiagent.agent.client.dto.OrderEstimateRequest;
import com.fancy.taxiagent.agent.client.dto.PriceEstimateView;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.state.OrderStateService;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import feign.FeignException;
import feign.RetryableException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 行程价格估算工具：调用订单服务 estimate 接口。
 *
 * <p>估算成功后把 traceId/estDistance/estPrice 等快照存入订单状态（est* 字段），
 * CreateOrderTool 校验与槽位一致后透传，order-service 据此跳过二次算路。</p>
 *
 * <p>上游 4xx 拒绝映射为 {@code ORDER_REQUEST_REJECTED}，5xx 与可重试失败映射为
 * {@code ORDER_UNAVAILABLE}（照 RagServiceClient 错误映射）；其余失败返回
 * "Tool execution failed: " 开头的说明。</p>
 */
@Component
public class OrderEstimateTool implements AgentTool {

    private final OrderServiceFeignClient orderServiceFeignClient;
    private final ObjectMapper objectMapper;
    private final OrderStateService orderStateService;

    public OrderEstimateTool(OrderServiceFeignClient orderServiceFeignClient, ObjectMapper objectMapper,
                             OrderStateService orderStateService) {
        this.orderServiceFeignClient = orderServiceFeignClient;
        this.objectMapper = objectMapper;
        this.orderStateService = orderStateService;
    }

    @Override
    public String name() {
        return "estimatePrice";
    }

    @Override
    public String description() {
        return "估算行程价格。参数：startLat/startLng/endLat/endLng 为起终点经纬度（数字），"
                + "vehicleType 车型（1 快车/2 优享/3 专车），isExpedited 是否加急（0/1）。";
    }

    @Override
    public Class<?> inputType() {
        return Arguments.class;
    }

    @Override
    public String execute(String jsonArgs, AgentExecutionContext context, AgentStreamSink sink) {
        try {
            Arguments arguments = objectMapper.readValue(jsonArgs, Arguments.class);
            PriceEstimateView view = orderServiceFeignClient.estimate(
                    context.authorization(),
                    context.traceId(),
                    new OrderEstimateRequest(
                            arguments.startLat(), arguments.startLng(),
                            arguments.endLat(), arguments.endLng(),
                            arguments.vehicleType(), arguments.isExpedited()));
            saveEstimateSnapshot(context.conversationId(), arguments, view);
            return objectMapper.writeValueAsString(view);
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

    private AgentExecutionException unavailable(Throwable cause) {
        return new AgentExecutionException("ORDER_UNAVAILABLE", "订单服务暂不可用", cause);
    }

    /**
     * 把本次估算入参与结果快照到订单状态，供 CreateOrderTool 与最终槽位比对复用。
     * isExpedited 入参缺省按 0 记（与槽位缺省口径一致）。
     */
    private void saveEstimateSnapshot(String conversationId, Arguments args, PriceEstimateView view) {
        Map<String, String> snapshot = new HashMap<>();
        snapshot.put("estTraceId", view.traceId());
        snapshot.put("estDistance", plain(view.estDistance()));
        snapshot.put("estPrice", plain(view.estPrice()));
        snapshot.put("estRadio", plain(view.estPriceRadio()));
        snapshot.put("estVehicleType", args.vehicleType() == null ? null : String.valueOf(args.vehicleType()));
        snapshot.put("estIsExpedited", args.isExpedited() == null ? "0" : String.valueOf(args.isExpedited()));
        snapshot.put("estStartLat", plain(args.startLat()));
        snapshot.put("estStartLng", plain(args.startLng()));
        snapshot.put("estEndLat", plain(args.endLat()));
        snapshot.put("estEndLng", plain(args.endLng()));
        orderStateService.setEstimate(conversationId, snapshot);
    }

    private String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /** 价格估算工具参数。 */
    public record Arguments(
            BigDecimal startLat,
            BigDecimal startLng,
            BigDecimal endLat,
            BigDecimal endLng,
            Integer vehicleType,
            Integer isExpedited
    ) {
    }
}
