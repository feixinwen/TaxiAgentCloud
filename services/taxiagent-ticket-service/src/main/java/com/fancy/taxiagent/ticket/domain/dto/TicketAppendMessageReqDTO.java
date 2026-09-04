package com.fancy.taxiagent.ticket.domain.dto;

public class TicketAppendMessageReqDTO {

    private String ticketId;
    private String content;

    public TicketAppendMessageReqDTO() {
    }

    public String getTicketId() {
        return ticketId;
    }

    public void setTicketId(String ticketId) {
        this.ticketId = ticketId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
