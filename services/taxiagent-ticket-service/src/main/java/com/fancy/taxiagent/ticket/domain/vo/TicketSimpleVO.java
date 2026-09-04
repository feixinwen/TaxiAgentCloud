package com.fancy.taxiagent.ticket.domain.vo;

import java.time.LocalDateTime;

public record TicketSimpleVO(
        String ticketId,
        LocalDateTime createdAt,
        Integer ticketType,
        String ticketTypeDesc,
        String title,
        Integer ticketStatus,
        String ticketStatusDesc
) {
}
