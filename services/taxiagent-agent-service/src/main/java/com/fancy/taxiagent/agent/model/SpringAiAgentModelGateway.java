package com.fancy.taxiagent.agent.model;

import com.fancy.taxiagent.agent.config.AgentModelProperties;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Spring AI 流、消息和工具调用与内部模型契约之间的适配器。
 */
public class SpringAiAgentModelGateway implements AgentModelGateway {

    private final ChatModel chatModel;
    private final AgentModelProperties modelProperties;
    private final List<ToolCallback> toolCallbacks;

    /**
     * 创建 Spring AI 模型适配器。
     *
     * @param chatModel Spring AI 聊天模型
     * @param modelProperties 模型配置
     * @param toolCallbacks 模型可见的工具定义（工具由 Agent 循环显式执行）
     */
    public SpringAiAgentModelGateway(ChatModel chatModel, AgentModelProperties modelProperties,
                                     List<ToolCallback> toolCallbacks) {
        this.chatModel = chatModel;
        this.modelProperties = modelProperties;
        this.toolCallbacks = toolCallbacks == null ? List.of() : List.copyOf(toolCallbacks);
    }

    /**
     * 将内部请求转换为 Spring AI Prompt，消费流并返回聚合后的内部助手轮次。
     *
     * <p>只向模型暴露 {@code allowedToolNames} 内的工具定义：白名单之外的工具模型
     * 根本看不到，从根本上避免模型选中后触发 TOOL_NOT_ALLOWED 的整轮失败。</p>
     *
     * @param request 模型请求
     * @param allowedToolNames 本 Agent 允许模型调用的工具名集合；空集合表示不暴露任何工具
     * @param deltaConsumer 公开正文增量消费者
     * @param timeout 本轮模型调用可使用的剩余 Run 时间
     * @return 聚合后的助手轮次
     */
    @Override
    public ModelTurn stream(ModelRequest request, Set<String> allowedToolNames,
                            Consumer<String> deltaConsumer, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw runTimeout(null);
        }
        List<ToolCallback> visibleToolCallbacks = toolCallbacks.stream()
                .filter(callback -> allowedToolNames != null
                        && allowedToolNames.contains(callback.getToolDefinition().name()))
                .toList();
        Prompt prompt = new Prompt(toMessages(request), OpenAiChatOptions.builder()
                .model(modelProperties.getName())
                .toolCallbacks(visibleToolCallbacks)
                .internalToolExecutionEnabled(false)
                .build());

        AtomicReference<ChatResponse> aggregatedResponse = new AtomicReference<>();
        Mono<Long> deadline = Mono.delay(timeout).cache();
        new MessageAggregator()
                .aggregate(
                        chatModel.stream(prompt)
                                .timeout(deadline, ignored -> deadline)
                                .onErrorMap(TimeoutException.class, this::runTimeout)
                                .onErrorMap(this::mapModelFailure)
                                .doOnNext(response -> publishDelta(response, deltaConsumer)),
                        aggregatedResponse::set)
                .blockLast();
        ChatResponse aggregated = aggregatedResponse.get();
        if (aggregated == null || aggregated.getResult() == null) {
            return ModelTurn.assistant("", List.of());
        }
        AssistantMessage output = aggregated.getResult().getOutput();
        List<ModelToolCall> toolCalls = output.getToolCalls().stream()
                .map(call -> new ModelToolCall(call.id(), call.name(), call.arguments()))
                .toList();
        return ModelTurn.assistant(output.getText(), toolCalls);
    }

    private AgentExecutionException runTimeout(Throwable cause) {
        return new AgentExecutionException("RUN_TIMEOUT", "Agent Run 已超时", cause);
    }

    private Throwable mapModelFailure(Throwable failure) {
        if (failure instanceof AgentExecutionException) {
            return failure;
        }
        if (failure instanceof TransientAiException
                || failure instanceof WebClientRequestException
                || isProviderServerFailure(failure)) {
            return new AgentExecutionException("MODEL_UNAVAILABLE", "模型服务暂不可用", null);
        }
        return failure;
    }

    private boolean isProviderServerFailure(Throwable failure) {
        return failure instanceof WebClientResponseException responseException
                && responseException.getStatusCode().is5xxServerError();
    }

    private List<Message> toMessages(ModelRequest request) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(request.systemPrompt()));
        for (ModelTurn turn : request.turns()) {
            switch (turn.role()) {
                case "user" -> messages.add(new UserMessage(turn.content()));
                case "assistant" -> messages.add(toAssistantMessage(turn));
                case "tool" -> messages.add(toToolResponseMessage(turn));
                default -> throw new IllegalArgumentException("不支持的模型消息角色: " + turn.role());
            }
        }
        return messages;
    }

    private AssistantMessage toAssistantMessage(ModelTurn turn) {
        List<AssistantMessage.ToolCall> toolCalls = turn.toolCalls().stream()
                .map(call -> new AssistantMessage.ToolCall(
                        call.id(), "function", call.name(), call.arguments()))
                .toList();
        return AssistantMessage.builder()
                .content(turn.content())
                .toolCalls(toolCalls)
                .build();
    }

    private ToolResponseMessage toToolResponseMessage(ModelTurn turn) {
        List<ToolResponseMessage.ToolResponse> responses = turn.toolResults().stream()
                .map(result -> new ToolResponseMessage.ToolResponse(
                        result.toolCallId(), result.toolName(), result.content()))
                .toList();
        return ToolResponseMessage.builder().responses(responses).build();
    }

    private void publishDelta(ChatResponse response, Consumer<String> deltaConsumer) {
        Generation generation = response.getResult();
        if (generation == null || generation.getOutput() == null) {
            return;
        }
        String content = generation.getOutput().getText();
        if (content != null && !content.isEmpty()) {
            deltaConsumer.accept(content);
        }
    }
}
