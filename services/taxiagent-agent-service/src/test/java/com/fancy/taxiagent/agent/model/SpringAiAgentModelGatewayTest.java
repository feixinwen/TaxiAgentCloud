package com.fancy.taxiagent.agent.model;

import com.fancy.taxiagent.agent.config.AgentModelProperties;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证内部模型记录与 Spring AI 消息、流和工具调用之间的转换。
 */
class SpringAiAgentModelGatewayTest {

    record TestArguments(String query, Integer topK) {
    }

    @Test
    void shouldMapPromptStreamDeltasAndToolCalls() {
        CapturingChatModel chatModel = new CapturingChatModel(List.of(
                response(AssistantMessage.builder().content("请稍候，").build()),
                response(AssistantMessage.builder()
                        .content("正在查询。")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1", "function", "searchKnowledgeBase", "{\"query\":\"取消费\"}")))
                        .build())
        ));
        AgentModelProperties properties = new AgentModelProperties();
        org.springframework.ai.tool.ToolCallback searchTool = org.springframework.ai.tool.function.FunctionToolCallback
                .<Object, String>builder("searchKnowledgeBase", args -> {
                    throw new IllegalStateException("测试中不执行");
                })
                .description("检索知识库")
                .inputType(TestArguments.class)
                .build();
        SpringAiAgentModelGateway gateway = new SpringAiAgentModelGateway(
                chatModel, properties, java.util.List.of(searchTool));
        List<String> deltas = new ArrayList<>();
        ModelRequest request = new ModelRequest(
                "仅依据知识库回答。",
                "support-v1",
                List.of(ModelTurn.user("取消订单收费吗？"))
        );

        ModelTurn result = gateway.stream(
                request, java.util.Set.of("searchKnowledgeBase"), deltas::add, Duration.ofSeconds(60));

        assertThat(deltas).containsExactly("请稍候，", "正在查询。");
        assertThat(result.role()).isEqualTo("assistant");
        assertThat(result.content()).isEqualTo("请稍候，正在查询。");
        assertThat(result.toolCalls()).containsExactly(
                new ModelToolCall("call-1", "searchKnowledgeBase", "{\"query\":\"取消费\"}"));

        Prompt prompt = chatModel.prompt.get();
        assertThat(prompt.getInstructions()).hasSize(2);
        assertThat(prompt.getInstructions().get(0)).isInstanceOf(SystemMessage.class);
        assertThat(prompt.getInstructions().get(0).getText()).isEqualTo("仅依据知识库回答。");
        assertThat(prompt.getInstructions().get(1)).isInstanceOf(UserMessage.class);
        assertThat(prompt.getInstructions().get(1).getText()).isEqualTo("取消订单收费吗？");
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
        assertThat(options.getModel()).isEqualTo("deepseek-chat");
        assertThat(options.getInternalToolExecutionEnabled()).isFalse();
        assertThat(options.getToolCallbacks()).singleElement()
                .satisfies(callback -> assertThat(callback.getToolDefinition().name())
                        .isEqualTo("searchKnowledgeBase"));
    }

    @Test
    void shouldOnlyExposeWhitelistedToolsToModel() {
        CapturingChatModel chatModel = new CapturingChatModel(List.of(
                response(AssistantMessage.builder().content("回答").build())
        ));
        org.springframework.ai.tool.ToolCallback allowedTool = org.springframework.ai.tool.function.FunctionToolCallback
                .<Object, String>builder("searchKnowledgeBase", args -> {
                    throw new IllegalStateException("测试中不执行");
                })
                .description("检索知识库")
                .inputType(TestArguments.class)
                .build();
        org.springframework.ai.tool.ToolCallback forbiddenTool = org.springframework.ai.tool.function.FunctionToolCallback
                .<Object, String>builder("deleteOrder", args -> {
                    throw new IllegalStateException("测试中不执行");
                })
                .description("删除订单")
                .inputType(TestArguments.class)
                .build();
        SpringAiAgentModelGateway gateway = new SpringAiAgentModelGateway(
                chatModel, new AgentModelProperties(), java.util.List.of(allowedTool, forbiddenTool));
        ModelRequest request = new ModelRequest(
                "system", "support-v1", List.of(ModelTurn.user("取消订单收费吗？")));

        gateway.stream(request, java.util.Set.of("searchKnowledgeBase"), ignored -> { },
                Duration.ofSeconds(60));

        OpenAiChatOptions options = (OpenAiChatOptions) chatModel.prompt.get().getOptions();
        assertThat(options.getToolCallbacks()).singleElement()
                .satisfies(callback -> assertThat(callback.getToolDefinition().name())
                        .isEqualTo("searchKnowledgeBase"));
        assertThat(options.getInternalToolExecutionEnabled()).isFalse();
    }

    @Test
    void shouldExposeNoToolsWhenWhitelistIsEmpty() {
        CapturingChatModel chatModel = new CapturingChatModel(List.of(
                response(AssistantMessage.builder().content("回答").build())
        ));
        org.springframework.ai.tool.ToolCallback searchTool = org.springframework.ai.tool.function.FunctionToolCallback
                .<Object, String>builder("searchKnowledgeBase", args -> {
                    throw new IllegalStateException("测试中不执行");
                })
                .description("检索知识库")
                .inputType(TestArguments.class)
                .build();
        SpringAiAgentModelGateway gateway = new SpringAiAgentModelGateway(
                chatModel, new AgentModelProperties(), java.util.List.of(searchTool));

        gateway.stream(new ModelRequest("system", "support-v1", List.of(ModelTurn.user("讲个笑话"))),
                java.util.Set.of(), ignored -> { }, Duration.ofSeconds(60));

        OpenAiChatOptions options = (OpenAiChatOptions) chatModel.prompt.get().getOptions();
        assertThat(options.getToolCallbacks()).isEmpty();
        assertThat(options.getInternalToolExecutionEnabled()).isFalse();
    }

    @Test
    void shouldMapAssistantToolCallAndToolResponseBackIntoNextPrompt() {
        CapturingChatModel chatModel = new CapturingChatModel(List.of(
                response(AssistantMessage.builder().content("最终答案").build())
        ));
        SpringAiAgentModelGateway gateway = new SpringAiAgentModelGateway(chatModel, new AgentModelProperties(), java.util.List.of());
        ModelToolCall call = new ModelToolCall(
                "call-2", "searchKnowledgeBase", "{\"query\":\"计价规则\"}");
        ModelToolResult toolResult = new ModelToolResult(
                "call-2", "searchKnowledgeBase", "[{\"answer\":\"规则内容\"}]");
        ModelRequest request = new ModelRequest(
                "system",
                "support-v1",
                List.of(
                        ModelTurn.user("怎么计价？"),
                        ModelTurn.assistant("", List.of(call)),
                        ModelTurn.tool(List.of(toolResult))
                )
        );

        gateway.stream(request, java.util.Set.of(), ignored -> { }, Duration.ofSeconds(60));

        List<Message> messages = chatModel.prompt.get().getInstructions();
        assertThat(messages).hasSize(4);
        assertThat(messages.get(2)).isInstanceOf(AssistantMessage.class);
        AssistantMessage assistant = (AssistantMessage) messages.get(2);
        assertThat(assistant.getToolCalls()).singleElement().satisfies(mapped -> {
            assertThat(mapped.id()).isEqualTo("call-2");
            assertThat(mapped.name()).isEqualTo("searchKnowledgeBase");
        });
        assertThat(messages.get(3)).isInstanceOf(ToolResponseMessage.class);
        ToolResponseMessage response = (ToolResponseMessage) messages.get(3);
        assertThat(response.getResponses()).containsExactly(
                new ToolResponseMessage.ToolResponse(
                        "call-2", "searchKnowledgeBase", "[{\"answer\":\"规则内容\"}]")
        );
    }

    @Test
    void shouldCancelStalledModelStreamAtRunDeadline() {
        ChatModel stalledModel = new CapturingChatModel(List.of()) {
            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.never();
            }
        };
        SpringAiAgentModelGateway gateway = new SpringAiAgentModelGateway(
                stalledModel, new AgentModelProperties(), java.util.List.of());

        assertThatThrownBy(() -> gateway.stream(
                new ModelRequest("system", "support-v1", List.of(ModelTurn.user("question"))),
                java.util.Set.of(),
                ignored -> { },
                Duration.ofMillis(20)))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("RUN_TIMEOUT");
    }

    @Test
    void shouldMapSpringAiTransientFailureToModelUnavailable() {
        assertModelUnavailable(new TransientAiException("sentinel-provider-transient-detail"));
    }

    @Test
    void shouldMapProviderHttpServerFailureToModelUnavailable() {
        assertModelUnavailable(WebClientResponseException.create(
                503,
                "Service Unavailable",
                HttpHeaders.EMPTY,
                new byte[0],
                null
        ));
    }

    @Test
    void shouldMapProviderConnectionFailureToModelUnavailable() {
        assertModelUnavailable(new WebClientRequestException(
                new ConnectException("sentinel-provider-connect-detail"),
                HttpMethod.POST,
                URI.create("https://model.invalid/chat/completions"),
                HttpHeaders.EMPTY
        ));
    }

    @Test
    void shouldPreserveExistingAgentExecutionException() {
        AgentExecutionException expected = new AgentExecutionException(
                "RUN_TIMEOUT", "Agent Run 已超时", null);

        assertThatThrownBy(() -> streamFailure(expected))
                .isSameAs(expected);
    }

    @Test
    void shouldNotHideLocalProgrammingFailureAsModelUnavailable() {
        IllegalStateException expected = new IllegalStateException("local-programming-error");

        assertThatThrownBy(() -> streamFailure(expected))
                .isSameAs(expected);
    }

    private void assertModelUnavailable(RuntimeException error) {
        assertThatThrownBy(() -> streamFailure(error))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(failure -> ((AgentExecutionException) failure).getCode())
                .isEqualTo("MODEL_UNAVAILABLE");
    }

    private void streamFailure(RuntimeException error) {
        ChatModel failedModel = new CapturingChatModel(List.of()) {
            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.error(error);
            }
        };
        new SpringAiAgentModelGateway(failedModel, new AgentModelProperties(), java.util.List.of()).stream(
                new ModelRequest("system", "support-v1", List.of(ModelTurn.user("question"))),
                java.util.Set.of(),
                ignored -> { },
                Duration.ofSeconds(60)
        );
    }

    private ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static class CapturingChatModel implements ChatModel {

        private final List<ChatResponse> responses;
        private final AtomicReference<Prompt> prompt = new AtomicReference<>();

        private CapturingChatModel(List<ChatResponse> responses) {
            this.responses = responses;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("测试只验证流式调用");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            this.prompt.set(prompt);
            return Flux.fromIterable(responses);
        }
    }
}
