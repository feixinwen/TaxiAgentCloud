package com.fancy.taxiagent.user.outbox;

/**
 * 用户领域事件类型。
 *
 * <p>对应 outbox 表 event_type 列，同时作为 RocketMQ 消息的 tag 使用。</p>
 */
public enum UserEventType {
    USER_DISABLED,
    USER_ACTIVATED,
    USER_DELETED,
    USER_ROLE_CHANGED
}
