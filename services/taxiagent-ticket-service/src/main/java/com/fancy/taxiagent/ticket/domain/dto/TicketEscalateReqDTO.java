package com.fancy.taxiagent.ticket.domain.dto;

public class TicketEscalateReqDTO {

    private String ticketId;
    private Integer targetLevel;
    private String reason;

    public TicketEscalateReqDTO() {
    }

    public String getTicketId() {
        return ticketId;
    }

    public void setTicketId(String ticketId) {
        this.ticketId = ticketId;
    }

    public Integer getTargetLevel() {
        return targetLevel;
    }

    public void setTargetLevel(Integer targetLevel) {
        this.targetLevel = targetLevel;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
