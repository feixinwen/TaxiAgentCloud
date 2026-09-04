package com.fancy.taxiagent.agent.model;

/**
 * 交回模型的工具执行结果。
 *
 * @param toolCallId 对应的工具调用标识
 * @param toolName 工具名称
 * @param content 提供给模型的结果文本
 */
public record ModelToolResult(String toolCallId, String toolName, String content) {
}
