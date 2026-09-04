package com.fancy.taxiagent.agent.client.dto;

/**
 * RAG 检索接口的请求载荷。
 *
 * @param question 待检索的问题
 * @param topK 返回的最大结果数
 */
public record RagSearchRequest(String question, Integer topK) {
}
