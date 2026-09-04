package com.fancy.taxiagent.agent.client.dto;

/**
 * 工单升级请求（targetLevel 2=紧急，3=特急）。
 */
public record TicketEscalateRequest(
        String ticketId,
        Integer targetLevel,
        String reason
) {
}
