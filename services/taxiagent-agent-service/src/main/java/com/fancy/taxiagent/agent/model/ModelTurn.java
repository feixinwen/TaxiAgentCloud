package com.fancy.taxiagent.agent.model;

import java.util.Collections;
import java.util.List;

/**
 * 模型对话中的一轮消息，统一表示用户、助手及工具响应。
 *
 * @param role 角色：user、assistant 或 tool
 * @param content 文本内容
 * @param toolCalls 助手发起的工具调用
 * @param toolResults 工具执行结果
 */
public record ModelTurn(
        String role,
        String content,
        List<ModelToolCall> toolCalls,
        List<ModelToolResult> toolResults
) {

    /**
     * 规范化可空内容并冻结集合，避免执行期间上下文被外部修改。
     */
    public ModelTurn {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? Collections.emptyList() : List.copyOf(toolCalls);
        toolResults = toolResults == null ? Collections.emptyList() : List.copyOf(toolResults);
    }

    /**
     * 创建用户消息。
     *
     * @param content 用户消息正文
     * @return 用户消息轮次
     */
    public static ModelTurn user(String content) {
        return new ModelTurn("user", content, List.of(), List.of());
    }

    /**
     * 创建助手消息。
     *
     * @param content 助手正文
     * @param toolCalls 助手请求的工具调用
     * @return 助手消息轮次
     */
    public static ModelTurn assistant(String content, List<ModelToolCall> toolCalls) {
        return new ModelTurn("assistant", content, toolCalls, List.of());
    }

    /**
     * 创建工具响应消息。
     *
     * @param toolResults 工具结果
     * @return 工具响应轮次
     */
    public static ModelTurn tool(List<ModelToolResult> toolResults) {
        return new ModelTurn("tool", "", List.of(), toolResults);
    }
}
