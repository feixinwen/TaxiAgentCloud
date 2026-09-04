package com.fancy.taxiagent.agent.model;

import com.fancy.taxiagent.agent.classify.ClassifierProperties;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;

/**
 * DashScope 兼容模式 Chat 网关（分类器专用，自封装避免 starter 版本耦合）。
 *
 * <p>无 key 或调用失败时抛 {@link AgentExecutionException}（CLASSIFIER_UNAVAILABLE），
 * 服务保持可启动。</p>
 */
@Component
public class DashScopeChatGateway {

    private static final String URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final int MAX_RETRIES = 3;
    private static final Logger log = LoggerFactory.getLogger(DashScopeChatGateway.class);

    private final RestClient restClient;
    private final String apiKey;
    private final ClassifierProperties properties;

    public DashScopeChatGateway(RestClient restClient,
                                @Value("${spring.ai.dashscope.api-key:}") String apiKey,
                                ClassifierProperties properties) {
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.properties = properties;
    }

    /**
     * 调用 DashScope 兼容模式 Chat 接口完成一次分类请求。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户输入
     * @param timeout      调用超时（暂未使用，为后续扩展预留）
     * @return 分类模型返回的文本内容
     * @throws AgentExecutionException 代码 CLASSIFIER_UNAVAILABLE：API Key 缺失、调用失败或输出无法解析
     */
    public String chat(String systemPrompt, String userPrompt, Duration timeout) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AgentExecutionException("CLASSIFIER_UNAVAILABLE", "分类模型未配置 API Key", null);
        }
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return doChat(systemPrompt, userPrompt);
            } catch (RuntimeException e) {
                last = e;
                log.warn("event=agent_classifier_call_failed attempt={}/{} error={}",
                        attempt, MAX_RETRIES, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(200L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw new AgentExecutionException("CLASSIFIER_UNAVAILABLE", "分类模型调用失败", last);
    }

    private String doChat(String systemPrompt, String userPrompt) {
        ChatRequest request = new ChatRequest(
                properties.getModel(),
                List.of(new ChatMessage("system", systemPrompt), new ChatMessage("user", userPrompt)),
                properties.getTemperature());
        ChatResponse response;
        try {
            response = restClient.post()
                    .uri(URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(request)
                    .retrieve()
                    .body(ChatResponse.class);
        } catch (RestClientException e) {
            throw new IllegalStateException("分类模型 HTTP 调用失败", e);
        }
        if (response == null || response.choices() == null || response.choices().isEmpty()
                || response.choices().get(0).message() == null
                || response.choices().get(0).message().content() == null) {
            throw new IllegalStateException("分类模型响应为空");
        }
        return response.choices().get(0).message().content().trim();
    }

    private record ChatRequest(String model, List<ChatMessage> messages, double temperature) {
    }

    private record ChatMessage(String role, String content) {
    }

    private record ChatResponse(List<Choice> choices) {
        private record Choice(ChatMessage message) {
        }
    }
}
