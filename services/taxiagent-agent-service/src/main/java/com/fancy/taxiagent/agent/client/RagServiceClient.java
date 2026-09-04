package com.fancy.taxiagent.agent.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.agent.client.dto.RagSearchRequest;
import com.fancy.taxiagent.agent.client.dto.RagSearchResult;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import feign.FeignException;
import feign.RetryableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * RAG Service 的防腐客户端，负责输入约束、结果限制和错误映射。
 */
@Service
public class RagServiceClient {

    private static final Logger log = LoggerFactory.getLogger(RagServiceClient.class);
    private static final int MAX_QUERY_LENGTH = 500;
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 5;
    private static final int MAX_RESULT_BYTES = 16 * 1024;
    private static final String RAG_SERVICE_TARGET = "taxiagent-rag-service";
    private static final ObjectMapper RESULT_SERIALIZER = new ObjectMapper();

    private final RagServiceFeignClient ragServiceFeignClient;

    /**
     * 创建 RAG 防腐客户端。
     *
     * @param ragServiceFeignClient RAG 的 Feign 调用契约
     */
    public RagServiceClient(RagServiceFeignClient ragServiceFeignClient) {
        this.ragServiceFeignClient = ragServiceFeignClient;
    }

    /**
     * 使用调用方显式提供的请求头检索知识库。
     *
     * @param authorization 调用方提供的 Authorization 请求头
     * @param traceId 调用方提供的 X-Trace-Id 请求头
     * @param query 用户查询文本
     * @param topK 期望返回的结果数；为空时取五，超过五时截断为五
     * @return 在 16 KiB 上限内的完整知识库结果条目
     */
    public List<RagSearchResult> searchKnowledgeBase(String authorization, String traceId, String query, Integer topK) {
        String normalizedQuery = normalizeQuery(query);
        int normalizedTopK = topK == null ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);
        long startNanos = System.nanoTime();
        try {
            List<RagSearchResult> results = ragServiceFeignClient.search(
                    authorization, traceId, new RagSearchRequest(normalizedQuery, normalizedTopK));
            List<RagSearchResult> truncatedResults = truncateResults(results);
            logRemoteCall(traceId, "success", startNanos);
            return truncatedResults;
        } catch (RetryableException exception) {
            logRemoteCall(traceId, "failure", startNanos);
            throw unavailable(exception);
        } catch (FeignException exception) {
            logRemoteCall(traceId, "failure", startNanos);
            if (exception.status() >= 400 && exception.status() < 500) {
                throw new AgentExecutionException(
                        "RAG_REQUEST_REJECTED", "RAG 请求被拒绝", exception);
            }
            throw unavailable(exception);
        } catch (AgentExecutionException exception) {
            logRemoteCall(traceId, "failure", startNanos);
            throw exception;
        }
    }

    private String normalizeQuery(String query) {
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isEmpty() || normalizedQuery.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("query 长度必须在 1 到 500 之间");
        }
        return normalizedQuery;
    }

    private List<RagSearchResult> truncateResults(List<RagSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }
        List<RagSearchResult> truncated = new ArrayList<>();
        for (RagSearchResult result : results) {
            if (result == null) {
                continue;
            }
            List<RagSearchResult> candidate = new ArrayList<>(truncated);
            candidate.add(result);
            if (serializedSize(candidate) > MAX_RESULT_BYTES) {
                break;
            }
            truncated.add(result);
        }
        return List.copyOf(truncated);
    }

    private int serializedSize(List<RagSearchResult> results) {
        try {
            return RESULT_SERIALIZER.writeValueAsBytes(results).length;
        } catch (JsonProcessingException exception) {
            throw unavailable(exception);
        }
    }

    private void logRemoteCall(String traceId, String status, long startNanos) {
        log.info(
                "event=rag_remote_call target={} traceId={} status={} durationMs={}",
                RAG_SERVICE_TARGET,
                traceId,
                status,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
        );
    }

    private AgentExecutionException unavailable(Throwable cause) {
        return new AgentExecutionException("RAG_UNAVAILABLE", "RAG 服务暂不可用", cause);
    }
}
