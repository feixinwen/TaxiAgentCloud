package com.fancy.taxiagent.order.domain.vo;

import java.math.BigDecimal;

public record PriceEstimateVO(
        BigDecimal estPrice,
        BigDecimal estPriceRadio,
        BigDecimal estPriceBase,
        BigDecimal estPriceTime,
        BigDecimal estPriceDistance,
        BigDecimal estPriceExpedited
) {
}
