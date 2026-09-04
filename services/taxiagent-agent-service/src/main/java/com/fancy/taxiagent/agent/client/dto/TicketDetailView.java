package com.fancy.taxiagent.agent.client.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单详情（ticket-service 下发的 *Desc 字段为可直接展示的中文描述）。
 */
public record TicketDetailView(
        String ticketId,
        String title,
        String content,
        String orderId,
        String ticketTypeDesc,
        String priorityDesc,
        String ticketStatusDesc,
        String processResult,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<TicketChatView> chatHistory
) {
}
