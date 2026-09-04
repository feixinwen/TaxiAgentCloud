package com.fancy.taxiagent.order.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record RideOrderVO(
        String orderId,
        String userId,
        String driverId,
        String estRoute,
        String realRoute,
        Integer vehicleType,
        Integer isReservation,
        Integer isExpedited,
        String safetyCode,
        Integer orderStatus,
        Integer cancelRole,
        String cancelReason,
        LocalDateTime createTime,
        LocalDateTime scheduledTime,
        LocalDateTime driverAcceptTime,
        LocalDateTime driverArriveTime,
        LocalDateTime pickupTime,
        LocalDateTime finishTime,
        LocalDateTime payTime,
        String startAddress,
        BigDecimal startLat,
        BigDecimal startLng,
        String endAddress,
        BigDecimal endLat,
        BigDecimal endLng,
        BigDecimal estDistance,
        BigDecimal realDistance,
        BigDecimal estPrice,
        BigDecimal realPrice,
        BigDecimal priceBase,
        BigDecimal priceTime,
        BigDecimal priceDistance,
        BigDecimal priceExpedited,
        BigDecimal priceRadio,
        LocalDateTime updateTime,
        Integer isDeleted
) {
}
