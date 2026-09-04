package com.fancy.taxiagent.agent.model;

import com.fancy.taxiagent.agent.classify.ClassifierProperties;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
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
 * DashScope Chat 网关：请求结构、无 key 降级与重试行为。
 */
class DashScopeChatGatewayTest {

    private DashScopeChatGateway gateway;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        ClassifierProperties properties = new ClassifierProperties();
        gateway = new DashScopeChatGateway(builder.build(), "test-key", properties);
    }

    @Test
    void shouldSendChatCompletionsRequest() {
        mockServer.expect(requestTo("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "model": "qwen3-max-preview",
                          "messages": [
                            {"role": "system", "content": "sys"},
                            {"role": "user", "content": "usr"}
                          ],
                          "temperature": 0.5
                        }
                        """))
                .andRespond(withSuccess("""
                        {"choices": [{"message": {"role": "assistant", "content": "SUPPORT"}}]}
                        """, MediaType.APPLICATION_JSON));

        String result = gateway.chat("sys", "usr", java.time.Duration.ofSeconds(15));

        assertThat(result).isEqualTo("SUPPORT");
        mockServer.verify();
    }

    @Test
    void shouldFailFastWithoutKey() {
        DashScopeChatGateway keyless = new DashScopeChatGateway(
                RestClient.builder().build(), "", new ClassifierProperties());

        assertThatThrownBy(() -> keyless.chat("sys", "usr", null))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("API Key");
    }

    @Test
    void shouldRetryAndSucceed() {
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/chat/completions")))
                .andRespond(withServerError());
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/chat/completions")))
                .andRespond(withSuccess("""
                        {"choices": [{"message": {"role": "assistant", "content": "DAILY"}}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(gateway.chat("sys", "usr", null)).isEqualTo("DAILY");
    }

    @Test
    void shouldFailAfterRetries() {
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/chat/completions")))
                .andRespond(withServerError());
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/chat/completions")))
                .andRespond(withServerError());
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/chat/completions")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> gateway.chat("sys", "usr", null))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("分类模型调用失败");
    }
}
