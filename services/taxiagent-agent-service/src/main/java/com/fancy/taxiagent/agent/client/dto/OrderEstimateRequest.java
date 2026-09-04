package com.fancy.taxiagent.agent.client.dto;

import java.math.BigDecimal;

/**
 * 订单服务价格估算请求。
 */
public record OrderEstimateRequest(
        BigDecimal startLat,
        BigDecimal startLng,
        BigDecimal endLat,
        BigDecimal endLng,
        Integer vehicleType,
        Integer isExpedited
) {
}
