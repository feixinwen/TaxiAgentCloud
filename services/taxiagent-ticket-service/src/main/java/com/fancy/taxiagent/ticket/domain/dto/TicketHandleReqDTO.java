package com.fancy.taxiagent.ticket.domain.dto;

public class TicketHandleReqDTO {

    public static final String ACTION_REPLY = "REPLY";
    public static final String ACTION_RESOLVE = "RESOLVE";
    public static final String ACTION_TRANSFER = "TRANSFER";
    public static final String ACTION_REJECT = "REJECT";

    private String ticketId;
    private String actionType;
    private String content;

    public TicketHandleReqDTO() {
    }

    public String getTicketId() {
        return ticketId;
    }

    public void setTicketId(String ticketId) {
        this.ticketId = ticketId;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
