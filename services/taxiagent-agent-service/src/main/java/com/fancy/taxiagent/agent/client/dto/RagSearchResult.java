package com.fancy.taxiagent.agent.client.dto;

/**
 * RAG 检索接口返回的一条知识库结果。
 *
 * @param groupId 知识组标识
 * @param question 命中的问题
 * @param answer 对应的答案
 */
public record RagSearchResult(String groupId, String question, String answer) {
}
