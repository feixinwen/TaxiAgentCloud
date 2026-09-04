package com.fancy.taxiagent.agent.client.dto;

import java.time.LocalDateTime;

/**
 * 工单沟通记录条目。
 */
public record TicketChatView(
        String senderRoleDesc,
        String content,
        LocalDateTime createdAt
) {
}
