package com.fancy.taxiagent.agent.client.dto;

/**
 * 创建工单请求（ userType 固定 1=乘客，由工具侧填充）。
 */
public record TicketCreateRequest(
        Integer userType,
        Long orderId,
        Integer ticketType,
        Integer priority,
        String title,
        String content
) {
}
