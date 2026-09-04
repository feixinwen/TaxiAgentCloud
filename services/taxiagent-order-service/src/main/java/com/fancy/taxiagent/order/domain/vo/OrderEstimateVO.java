package com.fancy.taxiagent.order.domain.vo;

import java.math.BigDecimal;

/**
 * 价格估算响应：计价明细 + 轨迹引用。
 *
 * <p>{@code traceId} 关联本次估算落 MongoDB 的预规划轨迹（{@code order_routes}），
 * 下单时透传可跳过二次算路；{@code estDistance} 为估算里程（公里）。</p>
 */
public record OrderEstimateVO(
        String traceId,
        BigDecimal estDistance,
        BigDecimal estPrice,
        BigDecimal estPriceRadio,
        BigDecimal estPriceBase,
        BigDecimal estPriceTime,
        BigDecimal estPriceDistance,
        BigDecimal estPriceExpedited
) {

    public static OrderEstimateVO of(String traceId, BigDecimal estDistance, PriceEstimateVO price) {
        return new OrderEstimateVO(traceId, estDistance, price.estPrice(), price.estPriceRadio(),
                price.estPriceBase(), price.estPriceTime(), price.estPriceDistance(), price.estPriceExpedited());
    }
}
