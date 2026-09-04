package com.fancy.taxiagent.order.domain.dto;

public class CancelOrderReqDTO {

    private Integer cancelRole;
    private String cancelReason;

    public CancelOrderReqDTO() {
    }

    public Integer getCancelRole() {
        return cancelRole;
    }

    public void setCancelRole(Integer cancelRole) {
        this.cancelRole = cancelRole;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }
}
