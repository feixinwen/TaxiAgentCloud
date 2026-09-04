package com.fancy.taxiagent.agent.client.dto;

/**
 * 工单补充信息请求。
 */
public record TicketAppendMessageRequest(
        String ticketId,
        String content
) {
}
