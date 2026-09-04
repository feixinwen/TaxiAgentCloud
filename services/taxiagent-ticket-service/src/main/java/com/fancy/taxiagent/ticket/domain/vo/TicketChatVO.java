package com.fancy.taxiagent.ticket.domain.vo;

import java.time.LocalDateTime;

public record TicketChatVO(
        String id,
        String ticketId,
        String senderId,
        Integer senderRole,
        String senderRoleDesc,
        String content,
        LocalDateTime createdAt
) {
}
