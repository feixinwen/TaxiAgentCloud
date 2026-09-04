package com.fancy.taxiagent.order.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class FinishRideReqDTO {

    private String orderId;
    private BigDecimal endLat;
    private BigDecimal endLng;
    private String endAddress;
    private String realPolyline;
    private LocalDateTime arriveTime;

    public FinishRideReqDTO() {
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public BigDecimal getEndLat() {
        return endLat;
    }

    public void setEndLat(BigDecimal endLat) {
        this.endLat = endLat;
    }

    public BigDecimal getEndLng() {
        return endLng;
    }

    public void setEndLng(BigDecimal endLng) {
        this.endLng = endLng;
    }

    public String getEndAddress() {
        return endAddress;
    }

    public void setEndAddress(String endAddress) {
        this.endAddress = endAddress;
    }

    public String getRealPolyline() {
        return realPolyline;
    }

    public void setRealPolyline(String realPolyline) {
        this.realPolyline = realPolyline;
    }

    public LocalDateTime getArriveTime() {
        return arriveTime;
    }

    public void setArriveTime(LocalDateTime arriveTime) {
        this.arriveTime = arriveTime;
    }
}
