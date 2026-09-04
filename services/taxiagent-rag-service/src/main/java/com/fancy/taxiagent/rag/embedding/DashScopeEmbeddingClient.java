package com.fancy.taxiagent.rag.embedding;

import com.fancy.taxiagent.rag.exception.EmbeddingErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * DashScope text-embedding-v4 客户端（自封装，避免 starter 版本耦合）。
 *
 * <p>POST /api/v1/services/embeddings/text-embedding/text-embedding，
 * 失败重试 3 次（200ms/400ms 退避），仍失败抛 {@link EmbeddingErrorException}。</p>
 */
@Component
public class DashScopeEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeEmbeddingClient.class);
    private static final String URL =
            "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";
    private static final int MAX_RETRIES = 3;

    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public DashScopeEmbeddingClient(RestClient.Builder builder,
                                    @Value("${spring.ai.dashscope.api-key:}") String apiKey,
                                    @Value("${spring.ai.dashscope.embedding.options.model:text-embedding-v4}") String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.restClient = builder.build();
    }

    public float[] embed(String text) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new EmbeddingErrorException("DASHSCOPE_API_KEY 未配置");
        }
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return doEmbed(text);
            } catch (EmbeddingErrorException | RestClientException e) {
                last = e;
                log.warn("event=rag_embedding_failed attempt={}/{} error={}", attempt, MAX_RETRIES, e.getMessage());
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
        throw new EmbeddingErrorException("embedding 调用失败(重试3次仍失败)", last);
    }

    private float[] doEmbed(String text) {
        DashScopeResponse response = restClient.post()
                .uri(URL)
                .header("Authorization", "Bearer " + apiKey)
                .body(new DashScopeRequest(model, text))
                .retrieve()
                .body(DashScopeResponse.class);
        if (response == null || response.output() == null || response.output().embeddings() == null
                || response.output().embeddings().isEmpty()) {
            throw new EmbeddingErrorException("embedding 响应为空");
        }
        List<Double> values = response.output().embeddings().get(0).embedding();
        if (values == null || values.isEmpty()) {
            throw new EmbeddingErrorException("embedding 向量为空");
        }
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i).floatValue();
        }
        return result;
    }

    private record DashScopeRequest(String model, Input input, Parameters parameters) {
        private DashScopeRequest(String model, String input) {
            this(model, new Input(List.of(input)), new Parameters("query", 1024));
        }

        private record Input(List<String> texts) {
        }

        private record Parameters(String text_type, int dimension) {
        }
    }

    private record DashScopeResponse(Output output, String code, String message) {
        private record Output(List<EmbeddingItem> embeddings) {
        }

        private record EmbeddingItem(List<Double> embedding) {
        }
    }
}
