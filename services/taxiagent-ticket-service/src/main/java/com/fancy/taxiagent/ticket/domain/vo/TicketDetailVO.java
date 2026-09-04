package com.fancy.taxiagent.ticket.domain.vo;

import java.time.LocalDateTime;
import java.util.List;

public record TicketDetailVO(
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
        String content,
        String processResult,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<TicketChatVO> chatHistory
) {
}
