package com.fancy.taxiagent.order.price;

import com.fancy.taxiagent.order.domain.vo.PriceEstimateVO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 唯一计价实现（规则与单体 RideOrderServiceImpl.calculatePrice 一致，收敛三处重复）。
 */
@Component
public class PriceCalculator {

    private static final int MONEY_SCALE = 2;

    public PriceEstimateVO calculate(String estKm, String estTime, Integer vehicleType, Integer isExpedited) {
        BigDecimal distanceKm = parseNonNegative(estKm);
        BigDecimal durationSec = parseNonNegative(estTime);
        boolean expedited = isExpedited != null && isExpedited == 1;
        int vehicleTypeCode = vehicleType == null ? 1 : vehicleType;

        // 1) 基础里程费：起步价 8.00 含 2.0km + 超出 2.20/km
        BigDecimal startPrice = new BigDecimal("8.00");
        BigDecimal overStartKm = distanceKm.subtract(new BigDecimal("2.0")).max(BigDecimal.ZERO);
        BigDecimal priceBase = startPrice.add(overStartKm.multiply(new BigDecimal("2.20")));

        // 2) 远途/空驶费：超出 20km 部分加收里程费的 50%
        BigDecimal longOverKm = distanceKm.subtract(new BigDecimal("20.0")).max(BigDecimal.ZERO);
        BigDecimal priceDistance = longOverKm.multiply(new BigDecimal("2.20").multiply(new BigDecimal("0.50")));

        // 3) 时长费：0.50/min，秒→分钟向上取整
        BigDecimal minutes = durationSec.divide(new BigDecimal("60"), 0, RoundingMode.CEILING);
        BigDecimal priceTime = minutes.multiply(new BigDecimal("0.50"));

        // 4) 加急固定调度费 5.00
        BigDecimal priceExpedited = expedited ? new BigDecimal("5.00") : BigDecimal.ZERO;

        // 5) 车型倍率 1->1.0, 2->1.6, 3->2.5；加急动态倍率 +0.2
        BigDecimal vehicleMultiplier = switch (vehicleTypeCode) {
            case 2 -> new BigDecimal("1.60");
            case 3 -> new BigDecimal("2.50");
            default -> BigDecimal.ONE;
        };
        BigDecimal finalMultiplier = vehicleMultiplier.add(expedited ? new BigDecimal("0.20") : BigDecimal.ZERO);

        // 6) 最终价 = (基础 + 时长 + 远途 + 固定加急) × 总倍率
        BigDecimal subTotal = priceBase.add(priceTime).add(priceDistance);
        BigDecimal estPrice = subTotal.add(priceExpedited).multiply(finalMultiplier);

        return new PriceEstimateVO(
                estPrice.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                finalMultiplier.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                priceBase.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                priceTime.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                priceDistance.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                priceExpedited.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    }

    private BigDecimal parseNonNegative(String text) {
        BigDecimal value;
        try {
            value = text == null ? BigDecimal.ZERO : new BigDecimal(text.trim());
        } catch (Exception ignored) {
            value = BigDecimal.ZERO;
        }
        return value.signum() < 0 ? BigDecimal.ZERO : value;
    }
}
