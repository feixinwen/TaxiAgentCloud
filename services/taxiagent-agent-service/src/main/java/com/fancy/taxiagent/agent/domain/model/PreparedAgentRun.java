package com.fancy.taxiagent.agent.domain.model;

/**
 * 消息准备事务提交后的 Agent Run 上下文。
 *
 * @param conversationDbId 对话内部主键
 * @param userMessageId 用户消息内部主键
 * @param runDbId Run 内部主键
 * @param runId 对外 Run UUID
 * @param content 规范化后的用户消息
 * @param sequenceNo 当前消息在对话内的序号
 */
public record PreparedAgentRun(
        long conversationDbId,
        long userMessageId,
        long runDbId,
        String runId,
        String content,
        long sequenceNo
) {
}
