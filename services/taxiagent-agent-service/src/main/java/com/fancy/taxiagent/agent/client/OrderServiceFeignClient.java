package com.fancy.taxiagent.agent.client;

import com.fancy.taxiagent.agent.client.dto.CreateOrderRequest;
import com.fancy.taxiagent.agent.client.dto.OrderEstimateRequest;
import com.fancy.taxiagent.agent.client.dto.PriceEstimateView;
import com.fancy.taxiagent.agent.config.OrderFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

/**
 * Order Service 的 Feign 调用契约（价格估算与下单）。
 */
@FeignClient(
        name = "taxiagent-order-service",
        path = "/api/orders",
        configuration = OrderFeignConfiguration.class
)
public interface OrderServiceFeignClient {

    /**
     * 携带调用方显式提供的认证与链路标识估算行程价格。
     *
     * @param authorization 调用方提供的 Authorization 请求头
     * @param traceId 调用方提供的 X-Trace-Id 请求头
     * @param request 估算请求
     * @return 价格估算结果
     */
    @PostMapping("/estimate")
    PriceEstimateView estimate(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("X-Trace-Id") String traceId,
            @RequestBody OrderEstimateRequest request
    );

    /**
     * 携带调用方显式提供的认证与链路标识创建订单。
     *
     * @param authorization 调用方提供的 Authorization 请求头
     * @param traceId 调用方提供的 X-Trace-Id 请求头
     * @param request 下单请求
     * @return 订单号
     */
    @PostMapping("")
    String createOrder(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("X-Trace-Id") String traceId,
            @RequestBody CreateOrderRequest request
    );
}
