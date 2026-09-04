package com.fancy.taxiagent.order.domain.vo;

import java.math.BigDecimal;

public record OrderBillVO(
        String orderId,
        BigDecimal realPrice,
        BigDecimal priceBase,
        BigDecimal priceTime,
        BigDecimal priceDistance,
        BigDecimal priceExpedited,
        BigDecimal priceRadio
) {
}
