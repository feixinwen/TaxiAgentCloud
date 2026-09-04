package com.fancy.taxiagent.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.agent.client.RagServiceClient;
import com.fancy.taxiagent.agent.client.dto.RagSearchResult;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库检索工具：将 RAG 防腐客户端适配为 AgentTool。
 *
 * <p>参数在 execute 内用 ObjectMapper 解析并校验为 {@link Arguments}；查询长度与
 * topK 边界在调用下游前校验，非法参数以 {@code RAG_REQUEST_REJECTED} 拒绝，
 * 与客服 Agent 原有的参数边界行为保持一致。</p>
 */
@Component
public class RagSearchTool implements AgentTool {

    private static final int MAX_QUERY_LENGTH = 500;

    private final RagServiceClient ragServiceClient;
    private final ObjectMapper objectMapper;

    public RagSearchTool(RagServiceClient ragServiceClient, ObjectMapper objectMapper) {
        this.ragServiceClient = ragServiceClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "searchKnowledgeBase";
    }

    @Override
    public String description() {
        return "检索出租车平台规则和客服知识。query 为完整问题，topK 可选且最大为 5。";
    }

    @Override
    public Class<?> inputType() {
        return Arguments.class;
    }

    @Override
    public String execute(String jsonArgs, AgentExecutionContext context, AgentStreamSink sink) {
        try {
            Arguments arguments = parseArguments(jsonArgs);
            List<RagSearchResult> results = ragServiceClient.searchKnowledgeBase(
                    context.authorization(), context.traceId(), arguments.query(), arguments.topK());
            return formatResults(results);
        } catch (AgentExecutionException exception) {
            throw exception;
        } catch (Exception exception) {
            return "Tool execution failed: " + exception.getMessage();
        }
    }

    private Arguments parseArguments(String json) {
        try {
            JsonNode root = objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
            if (!root.isObject() || !root.path("query").isTextual()) {
                throw invalidArguments(null);
            }
            String query = root.path("query").asText().trim();
            if (query.isEmpty() || query.length() > MAX_QUERY_LENGTH) {
                throw invalidArguments(null);
            }
            Integer topK = null;
            JsonNode topKNode = root.get("topK");
            if (topKNode != null && !topKNode.isNull()) {
                if (!topKNode.isIntegralNumber() || !topKNode.canConvertToInt()) {
                    throw invalidArguments(null);
                }
                int requestedTopK = topKNode.intValue();
                if (requestedTopK < 1) {
                    throw invalidArguments(null);
                }
                topK = requestedTopK;
            }
            return new Arguments(query, topK);
        } catch (JsonProcessingException exception) {
            throw invalidArguments(exception);
        }
    }

    private String formatResults(List<RagSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "知识库未找到相关条目。";
        }
        StringBuilder builder = new StringBuilder("找到以下相关知识库条目：");
        int index = 1;
        for (RagSearchResult result : results) {
            builder.append("\n--- 条目 ").append(index).append(" ---\n")
                    .append("参考问题：").append(result.question()).append("\n")
                    .append("标准回答：").append(result.answer());
            index++;
        }
        return builder.toString();
    }

    private AgentExecutionException invalidArguments(Throwable cause) {
        return new AgentExecutionException("RAG_REQUEST_REJECTED", "RAG 查询参数无效", cause);
    }

    /** 知识库检索工具参数。 */
    public record Arguments(String query, Integer topK) {
    }
}
