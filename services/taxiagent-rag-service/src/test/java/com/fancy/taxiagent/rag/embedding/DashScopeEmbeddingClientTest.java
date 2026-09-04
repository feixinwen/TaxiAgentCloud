package com.fancy.taxiagent.rag.embedding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * DashScope embedding 客户端：验证 v4 请求体结构（input.texts 数组 + text_type/dimension）与重试。
 */
class DashScopeEmbeddingClientTest {

    private DashScopeEmbeddingClient client;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new DashScopeEmbeddingClient(builder, "test-key", "text-embedding-v4");
    }

    @Test
    void shouldSendV4RequestBodyFormat() {
        mockServer.expect(requestTo("https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "model": "text-embedding-v4",
                          "input": {"texts": ["测试问题"]},
                          "parameters": {"text_type": "query", "dimension": 1024}
                        }
                        """))
                .andRespond(withSuccess("""
                        {
                          "output": {"embeddings": [{"embedding": [0.1, 0.2, 0.3]}]},
                          "request_id": "r1"
                        }
                        """, MediaType.APPLICATION_JSON));

        float[] result = client.embed("测试问题");

        assertThat(result).containsExactly(0.1f, 0.2f, 0.3f);
        mockServer.verify();
    }

    @Test
    void shouldRetryAndSucceed() {
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/text-embedding")))
                .andRespond(withServerError());
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/text-embedding")))
                .andRespond(withSuccess("""
                        {"output": {"embeddings": [{"embedding": [1.0]}]}}
                        """, MediaType.APPLICATION_JSON));

        float[] result = client.embed("测试");

        assertThat(result).containsExactly(1.0f);
    }

    @Test
    void shouldFailAfterRetries() {
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/text-embedding")))
                .andRespond(withServerError());
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/text-embedding")))
                .andRespond(withServerError());
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/text-embedding")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.embed("测试"))
                .hasMessageContaining("embedding 调用失败");
    }
}
