package com.fancy.taxiagent.order.price;

import com.fancy.taxiagent.order.domain.vo.PriceEstimateVO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PriceCalculatorTest {

    private final PriceCalculator calculator = new PriceCalculator();

    @Test
    void shouldApplyStartPriceWithinTwoKilometers() {
        // 1.5km, 300s, 快车: 起步 8.00 + 时长 ceil(300/60)=5min*0.5=2.50 → (8.00+2.50)*1.0=10.50
        PriceEstimateVO vo = calculator.calculate("1.5", "300", 1, 0);
        assertThat(vo.estPrice()).isEqualByComparingTo("10.50");
        assertThat(vo.estPriceBase()).isEqualByComparingTo("8.00");
        assertThat(vo.estPriceTime()).isEqualByComparingTo("2.50");
    }

    @Test
    void shouldChargePerKmBeyondTwoKilometers() {
        // 5km, 10min, 快车: 基础 8.00 + 3*2.20=6.60; 时长 ceil(600/60)=10*0.5=5.00 → 19.60
        PriceEstimateVO vo = calculator.calculate("5", "600", 1, 0);
        assertThat(vo.estPrice()).isEqualByComparingTo("19.60");
        assertThat(vo.estPriceBase()).isEqualByComparingTo("14.60");
    }

    @Test
    void shouldApplyLongDistanceSurchargeBeyondTwentyKm() {
        // 25km, 60min, 快车: 基础 8.00+23*2.20=58.60; 远途 5*1.10=5.50; 时长 60*0.5=30.00 → 94.10
        PriceEstimateVO vo = calculator.calculate("25", "3600", 1, 0);
        assertThat(vo.estPrice()).isEqualByComparingTo("94.10");
        assertThat(vo.estPriceDistance()).isEqualByComparingTo("5.50");
    }

    @Test
    void shouldCeilMinutes() {
        // 90秒 → ceil(90/60)=2 分钟 → 1.00
        PriceEstimateVO vo = calculator.calculate("2", "90", 1, 0);
        assertThat(vo.estPriceTime()).isEqualByComparingTo("1.00");
    }

    @Test
    void shouldApplyVehicleTypeMultiplier() {
        // 2km, 10min, 优享(1.6): (8.00 + 5.00) * 1.6 = 20.80
        PriceEstimateVO vo = calculator.calculate("2", "600", 2, 0);
        assertThat(vo.estPrice()).isEqualByComparingTo("20.80");
        // 专车(2.5)
        PriceEstimateVO vo3 = calculator.calculate("2", "600", 3, 0);
        assertThat(vo3.estPrice()).isEqualByComparingTo("32.50");
    }

    @Test
    void shouldApplyExpeditedFeeAndMultiplier() {
        // 2km, 10min, 快车, 加急: (8.00+5.00+5.00) * 1.2 = 21.60
        PriceEstimateVO vo = calculator.calculate("2", "600", 1, 1);
        assertThat(vo.estPrice()).isEqualByComparingTo("21.60");
        assertThat(vo.estPriceExpedited()).isEqualByComparingTo("5.00");
        assertThat(vo.estPriceRadio()).isEqualByComparingTo("1.20");
    }

    @Test
    void shouldRoundHalfUpToTwoDecimals() {
        // 3km, 1min, 快车: 基础 8.00+1*2.20=10.20; 时长 0.50 → 10.70
        PriceEstimateVO vo = calculator.calculate("3", "1", 1, 0);
        assertThat(vo.estPrice()).isEqualByComparingTo("10.70");
    }

    @Test
    void shouldTreatInvalidInputAsZero() {
        PriceEstimateVO vo = calculator.calculate(null, "abc", null, null);
        assertThat(vo.estPrice()).isEqualByComparingTo("8.00");
    }
}
