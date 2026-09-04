package com.fancy.taxiagent.ticket.domain.vo;

import java.time.LocalDateTime;

public record TicketVO(
        String id,
        String ticketId,
        String userId,
        Integer userType,
        String userTypeDesc,
        String orderId,
        Integer ticketType,
        String ticketTypeDesc,
        Integer priority,
        String priorityDesc,
        Integer ticketStatus,
        String ticketStatusDesc,
        String handlerId,
        String title,
        String contentSummary,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
