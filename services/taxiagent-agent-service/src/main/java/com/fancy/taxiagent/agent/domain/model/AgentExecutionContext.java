package com.fancy.taxiagent.agent.domain.model;

import com.fancy.taxiagent.agent.model.ModelTurn;

import java.util.Collections;
import java.util.List;

/**
 * 单次 Agent Run 的显式安全与对话上下文。
 *
 * @param userId 当前用户标识
 * @param conversationId 对外对话标识
 * @param runId 当前 Run 标识
 * @param authorization 原 Access Token 请求头，仅用于下游调用
 * @param traceId 链路追踪标识
 * @param userMessage 当前用户消息
 * @param history 最近的用户与助手历史
 */
public record AgentExecutionContext(
        long userId,
        String conversationId,
        String runId,
        String authorization,
        String traceId,
        String userMessage,
        List<ModelTurn> history
) {

    /**
     * 冻结历史快照，防止执行过程中被外部修改。
     */
    public AgentExecutionContext {
        history = history == null ? Collections.emptyList() : List.copyOf(history);
    }
}
