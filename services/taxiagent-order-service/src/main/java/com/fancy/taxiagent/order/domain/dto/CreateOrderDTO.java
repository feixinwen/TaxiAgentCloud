package com.fancy.taxiagent.order.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class CreateOrderDTO {

    private String mongoTraceId;
    private Integer vehicleType;
    private Integer isReservation;
    private Integer isExpedited;
    private LocalDateTime scheduledTime;
    private String startAddress;
    private BigDecimal startLat;
    private BigDecimal startLng;
    private String endAddress;
    private BigDecimal endLat;
    private BigDecimal endLng;
    private BigDecimal estPrice;
    private BigDecimal estDistance;
    private BigDecimal radio;

    public CreateOrderDTO() {
    }

    public String getMongoTraceId() {
        return mongoTraceId;
    }

    public void setMongoTraceId(String mongoTraceId) {
        this.mongoTraceId = mongoTraceId;
    }

    public Integer getVehicleType() {
        return vehicleType;
    }

    public void setVehicleType(Integer vehicleType) {
        this.vehicleType = vehicleType;
    }

    public Integer getIsReservation() {
        return isReservation;
    }

    public void setIsReservation(Integer isReservation) {
        this.isReservation = isReservation;
    }

    public Integer getIsExpedited() {
        return isExpedited;
    }

    public void setIsExpedited(Integer isExpedited) {
        this.isExpedited = isExpedited;
    }

    public LocalDateTime getScheduledTime() {
        return scheduledTime;
    }

    public void setScheduledTime(LocalDateTime scheduledTime) {
        this.scheduledTime = scheduledTime;
    }

    public String getStartAddress() {
        return startAddress;
    }

    public void setStartAddress(String startAddress) {
        this.startAddress = startAddress;
    }

    public BigDecimal getStartLat() {
        return startLat;
    }

    public void setStartLat(BigDecimal startLat) {
        this.startLat = startLat;
    }

    public BigDecimal getStartLng() {
        return startLng;
    }

    public void setStartLng(BigDecimal startLng) {
        this.startLng = startLng;
    }

    public String getEndAddress() {
        return endAddress;
    }

    public void setEndAddress(String endAddress) {
        this.endAddress = endAddress;
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

    public BigDecimal getEstPrice() {
        return estPrice;
    }

    public void setEstPrice(BigDecimal estPrice) {
        this.estPrice = estPrice;
    }

    public BigDecimal getEstDistance() {
        return estDistance;
    }

    public void setEstDistance(BigDecimal estDistance) {
        this.estDistance = estDistance;
    }

    public BigDecimal getRadio() {
        return radio;
    }

    public void setRadio(BigDecimal radio) {
        this.radio = radio;
    }
}
