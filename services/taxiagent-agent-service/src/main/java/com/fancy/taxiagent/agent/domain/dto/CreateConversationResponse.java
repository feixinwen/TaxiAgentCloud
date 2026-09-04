package com.fancy.taxiagent.agent.domain.dto;

import com.fancy.taxiagent.agent.domain.enums.ConversationStatus;

import java.time.LocalDateTime;

/**
 * 创建 Agent 对话后的公开结果。
 *
 * @param conversationId 对外 UUID 对话标识
 * @param status 对话状态
 * @param createdAt 创建时间
 */
public record CreateConversationResponse(
        String conversationId,
        ConversationStatus status,
        LocalDateTime createdAt
) {
}
