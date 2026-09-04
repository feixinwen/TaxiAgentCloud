package com.fancy.taxiagent.agent.domain.dto;

import java.time.LocalDateTime;

/**
 * 对外历史消息（messageId 为字符串化 Snowflake，规避 JS 精度丢失；
 * 只含 USER/ASSISTANT 角色——服务端从不持久化其他角色）。
 */
public record MessageResponse(
        String messageId,
        String role,
        String content,
        Long sequenceNo,
        LocalDateTime createdAt
) {
}
