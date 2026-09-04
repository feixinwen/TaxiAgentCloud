package com.fancy.taxiagent.agent.client.dto;

import java.time.LocalDateTime;

/**
 * 工单列表条目（未完结工单摘要）。
 */
public record TicketSimpleView(
        String ticketId,
        String title,
        String ticketTypeDesc,
        String ticketStatusDesc,
        LocalDateTime createdAt
) {
}
