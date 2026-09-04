package com.fancy.taxiagent.agent.client;

import com.fancy.taxiagent.agent.client.dto.RagSearchRequest;
import com.fancy.taxiagent.agent.client.dto.RagSearchResult;
import com.fancy.taxiagent.agent.config.AgentFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;

/**
 * RAG Service 的 Feign 调用契约。
 */
@FeignClient(
        name = "taxiagent-rag-service",
        path = "/api/rag",
        configuration = AgentFeignConfiguration.class
)
public interface RagServiceFeignClient {

    /**
     * 携带调用方显式提供的认证与链路标识检索知识库。
     *
     * @param authorization 调用方提供的 Authorization 请求头
     * @param traceId 调用方提供的 X-Trace-Id 请求头
     * @param request RAG 检索请求
     * @return RAG 检索结果
     */
    @PostMapping("/search")
    List<RagSearchResult> search(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("X-Trace-Id") String traceId,
            @RequestBody RagSearchRequest request
    );
}
