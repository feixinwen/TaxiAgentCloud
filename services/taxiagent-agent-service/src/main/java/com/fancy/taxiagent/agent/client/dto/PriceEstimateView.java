package com.fancy.taxiagent.agent.client.dto;

import java.math.BigDecimal;

/**
 * 订单服务价格估算结果（对齐 order-service OrderEstimateVO 字段）。
 *
 * <p>{@code traceId} 关联估算时落 MongoDB 的预规划轨迹，{@code estDistance} 为估算里程，
 * 下单时随槽位一并透传，order-service 即可跳过二次算路。</p>
 */
public record PriceEstimateView(
        String traceId,
        BigDecimal estDistance,
        BigDecimal estPrice,
        BigDecimal estPriceRadio,
        BigDecimal estPriceBase,
        BigDecimal estPriceTime,
        BigDecimal estPriceDistance,
        BigDecimal estPriceExpedited
) {
}
