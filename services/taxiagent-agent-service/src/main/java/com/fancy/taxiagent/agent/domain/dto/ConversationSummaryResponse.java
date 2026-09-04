package com.fancy.taxiagent.agent.domain.dto;

import com.fancy.taxiagent.agent.domain.enums.ConversationStatus;

import java.time.LocalDateTime;

/**
 * 对外对话摘要（不含内部主键 id/userId；仅暴露 UUID 形式的 conversationId）。
 *
 * <p>{@code title} 取自该对话首条用户消息（截断），首条消息发出前为 null，
 * 前端可回退展示时间/状态。</p>
 */
public record ConversationSummaryResponse(
        String conversationId,
        String title,
        ConversationStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
