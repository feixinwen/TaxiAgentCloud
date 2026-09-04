package com.fancy.taxiagent.auth.consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 用户领域事件消息体（topic {@code user-events}）。
 *
 * <p>{@code role} 字段在所有事件类型上都可能存在（并非只有角色变更事件携带），
 * 解析时容忍其缺失或额外未知字段。</p>
 *
 * @param eventId   事件唯一标识（幂等去重键）
 * @param userId    用户ID
 * @param eventType 事件类型（USER_DISABLED/USER_ACTIVATED/USER_DELETED/USER_ROLE_CHANGED）
 * @param role      用户业务角色（可能缺失）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserEventPayload(String eventId, Long userId, String eventType, String role) {
}
