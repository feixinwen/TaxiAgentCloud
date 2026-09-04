package com.fancy.taxiagent.agent.model;

import java.util.Collections;
import java.util.List;

/**
 * Agent 向模型网关提交的稳定请求契约。
 *
 * @param systemPrompt 固定系统提示词
 * @param promptVersion 提示词版本
 * @param turns 有序对话轮次
 */
public record ModelRequest(String systemPrompt, String promptVersion, List<ModelTurn> turns) {

    /**
     * 冻结轮次快照，避免流式调用期间发生并发修改。
     */
    public ModelRequest {
        turns = turns == null ? Collections.emptyList() : List.copyOf(turns);
    }
}
