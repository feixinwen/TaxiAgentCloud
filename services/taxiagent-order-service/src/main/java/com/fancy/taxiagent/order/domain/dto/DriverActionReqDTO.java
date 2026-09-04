package com.fancy.taxiagent.order.domain.dto;

public class DriverActionReqDTO {

    private String orderId;

    public DriverActionReqDTO() {
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }
}
