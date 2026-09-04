package com.fancy.taxiagent.agent.client.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 下单请求（字段对齐 order-service {@code CreateOrderDTO}）。
 *
 * <p>{@code mongoTraceId}/{@code estPrice}/{@code estDistance}/{@code radio} 传估算快照值时，
 * order-service 跳过二次算路直接落库；传 null 时由 order-service 自行规划路线、
 * 生成轨迹并计价（见 OrderServiceImpl.createOrder）。</p>
 */
public record CreateOrderRequest(
        String mongoTraceId,
        Integer vehicleType,
        Integer isReservation,
        Integer isExpedited,
        LocalDateTime scheduledTime,
        String startAddress,
        BigDecimal startLat,
        BigDecimal startLng,
        String endAddress,
        BigDecimal endLat,
        BigDecimal endLng,
        BigDecimal estPrice,
        BigDecimal estDistance,
        BigDecimal radio
) {
}
