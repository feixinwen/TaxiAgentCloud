package com.fancy.taxiagent.agent.classify;

import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.model.DashScopeChatGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClassifierServiceTest {

    private DashScopeChatGateway chatGateway;
    private ClassifierService classifierService;

    @BeforeEach
    void setUp() {
        chatGateway = mock(DashScopeChatGateway.class);
        ClassifierProperties properties = new ClassifierProperties();
        classifierService = new ClassifierService(chatGateway, properties);
    }

    @Test
    void shouldClassifyNewConversationWithOnlyUserMessage() {
        when(chatGateway.chat(any(), eq("帮我叫个车"), any())).thenReturn("ORDER");

        String label = classifierService.classify(null, null, "帮我叫个车");

        assertThat(label).isEqualTo("ORDER");
    }

    @Test
    void shouldClassifyWithContextWhenHistoryExists() {
        when(chatGateway.chat(any(), contains("上一次路由给SUPPORT方面的智能体"), any())).thenReturn("SUPPORT");

        String label = classifierService.classify("SUPPORT", "已为您查询", "我的发票呢");

        assertThat(label).isEqualTo("SUPPORT");
        verify(chatGateway).chat(any(), contains("助理最后的回复是：已为您查询"), any());
    }

    @Test
    void shouldNormalizeOutputToUpperCase() {
        when(chatGateway.chat(any(), any(), any())).thenReturn(" daily ");

        assertThat(classifierService.classify(null, null, "今天天气")).isEqualTo("DAILY");
    }

    @Test
    void shouldRejectUnknownLabel() {
        when(chatGateway.chat(any(), any(), any())).thenReturn("MAYBE");

        assertThatThrownBy(() -> classifierService.classify(null, null, "测试"))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("CLASSIFIER_UNAVAILABLE");
    }

    @Test
    void shouldPropagateUnavailableFromGateway() {
        when(chatGateway.chat(any(), any(), any()))
                .thenThrow(new AgentExecutionException("CLASSIFIER_UNAVAILABLE", "分类模型未配置 API Key", null));

        assertThatThrownBy(() -> classifierService.classify(null, null, "测试"))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("CLASSIFIER_UNAVAILABLE");
    }
}
