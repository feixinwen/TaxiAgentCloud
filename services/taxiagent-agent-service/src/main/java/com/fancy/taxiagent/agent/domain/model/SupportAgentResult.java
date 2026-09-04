package com.fancy.taxiagent.agent.domain.model;

/**
 * SupportAgent 成功执行后的聚合结果。
 *
 * @param content 已向客户端发送的完整助手正文
 * @param modelRounds 实际模型调用轮数
 * @param toolCalls 实际执行工具次数
 * @param promptVersion 使用的提示词版本
 */
public record SupportAgentResult(String content, int modelRounds, int toolCalls, String promptVersion) {
}
