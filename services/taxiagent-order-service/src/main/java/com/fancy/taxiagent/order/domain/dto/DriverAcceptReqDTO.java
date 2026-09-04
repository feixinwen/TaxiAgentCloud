package com.fancy.taxiagent.order.domain.dto;

public class DriverAcceptReqDTO {

    private String orderId;

    public DriverAcceptReqDTO() {
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }
}
