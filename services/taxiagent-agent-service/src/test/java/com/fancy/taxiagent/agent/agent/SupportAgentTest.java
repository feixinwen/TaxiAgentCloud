package com.fancy.taxiagent.agent.agent;

import com.fancy.taxiagent.agent.client.RagServiceClient;
import com.fancy.taxiagent.agent.client.TicketServiceFeignClient;
import com.fancy.taxiagent.agent.client.dto.RagSearchResult;
import com.fancy.taxiagent.agent.config.AgentExecutionProperties;
import com.fancy.taxiagent.agent.config.AgentModelProperties;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.domain.model.SupportAgentResult;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.loop.AgentLoop;
import com.fancy.taxiagent.agent.model.AgentModelGateway;
import com.fancy.taxiagent.agent.model.ModelRequest;
import com.fancy.taxiagent.agent.model.ModelToolCall;
import com.fancy.taxiagent.agent.model.ModelTurn;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import com.fancy.taxiagent.agent.tool.CreateTicketTool;
import com.fancy.taxiagent.agent.tool.RagSearchTool;
import com.fancy.taxiagent.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 SupportAgent 委托共享 AgentLoop 后的受控工具循环、安全边界和公开流事件。
 *
 * <p>循环语义（流式模型调用、工具白名单检查、截止时间、轮数与工具次数限制）由
 * AgentLoop + ToolRegistry + RagSearchTool 提供；本测试以真实 AgentLoop 驱动，
 * 同时作为 AgentLoop 的行为测试。</p>
 */
class SupportAgentTest {

    private static final String AUTHORIZATION = "Bearer private-token";
    private static final String TRACE_ID = "trace-support-001";
    private static final AgentExecutionContext CONTEXT = new AgentExecutionContext(
            50001L,
            "550e8400-e29b-41d4-a716-446655440000",
            "run-001",
            AUTHORIZATION,
            TRACE_ID,
            "取消订单会收费吗？",
            List.of()
    );

    @Test
    void shouldStreamPlainTextAndReturnFinalAnswerWithoutTools() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(ModelTurn.assistant("取消前请先查看当前订单状态。", List.of()));
        CapturingSink sink = new CapturingSink();
        SupportAgent agent = agent(gateway, mock(RagServiceClient.class));

        SupportAgentResult result = agent.execute(CONTEXT, sink);

        assertThat(result.content()).isEqualTo("取消前请先查看当前订单状态。");
        assertThat(result.modelRounds()).isEqualTo(1);
        assertThat(result.toolCalls()).isZero();
        assertThat(result.promptVersion()).isEqualTo("support-v1");
        assertThat(sink.events).containsExactly("delta:取消前请先查看当前订单状态。");
        assertThat(gateway.requests).singleElement().satisfies(request -> {
            assertThat(request.promptVersion()).isEqualTo("support-v1");
            assertThat(request.systemPrompt()).contains("知识库", "不得编造");
            assertThat(request.turns()).containsExactly(ModelTurn.user("取消订单会收费吗？"));
        });
    }

    @Test
    void shouldExecuteRagToolThenContinueModelWithToolResult() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(ModelTurn.assistant("", List.of(
                new ModelToolCall("call-1", "searchKnowledgeBase", "{\"query\":\" 取消费规则 \" ,\"topK\":3}"))));
        gateway.enqueue(ModelTurn.assistant("根据规则，符合条件时不会收取取消费。", List.of()));
        RagServiceClient ragClient = mock(RagServiceClient.class);
        when(ragClient.searchKnowledgeBase(AUTHORIZATION, TRACE_ID, "取消费规则", 3))
                .thenReturn(List.of(new RagSearchResult("rules", "取消费规则", "满足条件时不收费")));
        CapturingSink sink = new CapturingSink();

        SupportAgentResult result = agent(gateway, ragClient).execute(CONTEXT, sink);

        assertThat(result.content()).isEqualTo("根据规则，符合条件时不会收取取消费。");
        assertThat(result.modelRounds()).isEqualTo(2);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(sink.events).containsExactly(
                "tool-started:call-1:searchKnowledgeBase",
                "tool-completed:call-1:searchKnowledgeBase:true",
                "delta:根据规则，符合条件时不会收取取消费。"
        );
        verify(ragClient).searchKnowledgeBase(AUTHORIZATION, TRACE_ID, "取消费规则", 3);
        assertThat(gateway.requests.get(1).turns()).hasSize(3);
        assertThat(gateway.requests.get(1).turns().get(1).toolCalls())
                .extracting(ModelToolCall::id)
                .containsExactly("call-1");
        assertThat(gateway.requests.get(1).turns().get(2).toolResults()).singleElement().satisfies(toolResult -> {
            assertThat(toolResult.toolCallId()).isEqualTo("call-1");
            assertThat(toolResult.content()).contains("满足条件时不收费");
        });
    }

    @Test
    void shouldRejectUnknownToolWithoutPublishingOrExecutingIt() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(ModelTurn.assistant("", List.of(
                new ModelToolCall("call-evil", "deleteOrder", "{}"))));
        RagServiceClient ragClient = mock(RagServiceClient.class);
        CapturingSink sink = new CapturingSink();

        assertThatThrownBy(() -> agent(gateway, ragClient).execute(CONTEXT, sink))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("TOOL_NOT_ALLOWED");
        assertThat(sink.events).isEmpty();
        verify(ragClient, never()).searchKnowledgeBase(any(), any(), any(), any());
    }

    @Test
    void shouldRejectInvalidRagArgumentsBeforeCallingDownstream() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(ModelTurn.assistant("", List.of(
                new ModelToolCall("call-invalid", "searchKnowledgeBase", "{\"query\":\"question\",\"topK\":0}"))));
        RagServiceClient ragClient = mock(RagServiceClient.class);

        assertThatThrownBy(() -> agent(gateway, ragClient).execute(CONTEXT, new CapturingSink()))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("RAG_REQUEST_REJECTED");
        verify(ragClient, never()).searchKnowledgeBase(any(), any(), any(), any());
    }

    @Test
    void shouldRejectFractionalTopKBeforeCallingDownstream() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(ModelTurn.assistant("", List.of(
                new ModelToolCall("call-invalid", "searchKnowledgeBase", "{\"query\":\"question\",\"topK\":1.5}"))));
        RagServiceClient ragClient = mock(RagServiceClient.class);

        assertThatThrownBy(() -> agent(gateway, ragClient).execute(CONTEXT, new CapturingSink()))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("RAG_REQUEST_REJECTED");
        verify(ragClient, never()).searchKnowledgeBase(any(), any(), any(), any());
    }

    @Test
    void shouldStopAtFiveModelRoundsBeforeExecutingAnUnusableFinalToolCall() {
        FakeGateway gateway = repeatedToolGateway(5);
        RagServiceClient ragClient = mock(RagServiceClient.class);
        when(ragClient.searchKnowledgeBase(any(), any(), any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> agent(gateway, ragClient).execute(CONTEXT, new CapturingSink()))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("TOOL_LIMIT_EXCEEDED");
        assertThat(gateway.requests).hasSize(5);
        verify(ragClient, times(4)).searchKnowledgeBase(any(), any(), any(), any());
    }

    @Test
    void shouldExecuteAtMostEightTools() {
        List<ModelToolCall> calls = new ArrayList<>();
        for (int index = 1; index <= 9; index++) {
            calls.add(new ModelToolCall("call-" + index, "searchKnowledgeBase", "{\"query\":\"规则\"}"));
        }
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(ModelTurn.assistant("", calls));
        RagServiceClient ragClient = mock(RagServiceClient.class);
        when(ragClient.searchKnowledgeBase(any(), any(), any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> agent(gateway, ragClient).execute(CONTEXT, new CapturingSink()))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("TOOL_LIMIT_EXCEEDED");
        // topK 由 RagSearchTool 原样透传（归一化在真实 RagServiceClient 内），此处为 null
        verify(ragClient, times(8)).searchKnowledgeBase(any(), any(), eq("规则"), any());
    }

    @Test
    void shouldStopWhenRunDeadlineIsExceeded() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(ModelTurn.assistant("", List.of()));
        LongSupplier nanoTime = new SequenceNanoTime(
                0L, 0L, 0L, 0L, Duration.ofSeconds(61).toNanos(), Duration.ofSeconds(61).toNanos());
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AgentLoop.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> agent(gateway, mock(RagServiceClient.class), nanoTime)
                    .execute(CONTEXT, new CapturingSink()))
                    .isInstanceOf(AgentExecutionException.class)
                    .extracting(error -> ((AgentExecutionException) error).getCode())
                    .isEqualTo("RUN_TIMEOUT");
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains("event=agent_model_call_finished", "status=failure",
                                    "errorCode=RUN_TIMEOUT"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void shouldLogStableFailureCodeWhenModelCallFails() {
        AgentModelGateway gateway = (request, allowedToolNames, deltaConsumer, timeout) -> {
            throw new AgentExecutionException("MODEL_UNAVAILABLE", "model unavailable", null);
        };
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AgentLoop.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> agent(gateway, mock(RagServiceClient.class), System::nanoTime)
                    .execute(CONTEXT, new CapturingSink()))
                    .isInstanceOf(AgentExecutionException.class);

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains("event=agent_model_call_finished", "status=failure",
                                    "errorCode=MODEL_UNAVAILABLE", TRACE_ID)
                            .doesNotContain("private-token", "model unavailable"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void shouldCreateTicketThroughWhitelistedToolAndFeedResultBack() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(ModelTurn.assistant("", List.of(new ModelToolCall("call-ticket", "createTicket",
                "{\"ticketType\":3,\"title\":\"服务投诉\",\"content\":\"司机中途甩客\"}"))));
        gateway.enqueue(ModelTurn.assistant("已为您创建工单，编号 FT001。", List.of()));
        when(ticketServiceFeignClient.submitTicket(any(), any(), any())).thenReturn("FT001");
        CapturingSink sink = new CapturingSink();

        SupportAgentResult result = agent(gateway, mock(RagServiceClient.class)).execute(CONTEXT, sink);

        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(sink.events).contains(
                "tool-started:call-ticket:createTicket",
                "tool-completed:call-ticket:createTicket:true",
                "delta:已为您创建工单，编号 FT001。");
        verify(ticketServiceFeignClient).submitTicket(eq(AUTHORIZATION), eq(TRACE_ID), argThat(request ->
                request.userType() == 1
                        && request.ticketType() == 3
                        && request.priority() == 1
                        && "服务投诉".equals(request.title())
                        && "司机中途甩客".equals(request.content())));
        assertThat(gateway.requests.get(1).turns().get(2).toolResults()).singleElement()
                .satisfies(toolResult -> assertThat(toolResult.content()).contains("工单已创建，工单编号：FT001"));
    }

    @Test
    void shouldTellModelNotToInventPolicyWhenRagReturnsNoResults() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(ModelTurn.assistant("", List.of(
                new ModelToolCall("call-empty", "searchKnowledgeBase", "{\"query\":\"偏远地区取消规则\"}"))));
        gateway.enqueue(ModelTurn.assistant("未找到可靠规则，请稍后重试或联系人工客服。", List.of()));
        RagServiceClient ragClient = mock(RagServiceClient.class);
        when(ragClient.searchKnowledgeBase(any(), any(), any(), any())).thenReturn(List.of());

        agent(gateway, ragClient).execute(CONTEXT, new CapturingSink());

        assertThat(gateway.requests.get(1).turns().get(2).toolResults()).singleElement()
                .satisfies(result -> assertThat(result.content())
                        .contains("知识库未找到相关条目"));
    }

    private final TicketServiceFeignClient ticketServiceFeignClient = mock(TicketServiceFeignClient.class);

    private SupportAgent agent(AgentModelGateway gateway, RagServiceClient ragClient) {
        return agent(gateway, ragClient, System::nanoTime);
    }

    private SupportAgent agent(AgentModelGateway gateway, RagServiceClient ragClient, LongSupplier nanoTime) {
        AgentExecutionProperties executionProperties = new AgentExecutionProperties();
        AgentModelProperties modelProperties = new AgentModelProperties();
        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                new RagSearchTool(ragClient, new ObjectMapper()),
                new CreateTicketTool(ticketServiceFeignClient, new ObjectMapper())));
        AgentLoop agentLoop = new AgentLoop(
                gateway, executionProperties, modelProperties, toolRegistry, nanoTime);
        return new SupportAgent(agentLoop, toolRegistry);
    }

    private FakeGateway repeatedToolGateway(int count) {
        FakeGateway gateway = new FakeGateway();
        for (int index = 1; index <= count; index++) {
            gateway.enqueue(ModelTurn.assistant("", List.of(new ModelToolCall(
                    "call-" + index, "searchKnowledgeBase", "{\"query\":\"规则\"}"))));
        }
        return gateway;
    }

    private static final class FakeGateway implements AgentModelGateway {

        private final Queue<ModelTurn> turns = new ArrayDeque<>();
        private final List<ModelRequest> requests = new ArrayList<>();

        void enqueue(ModelTurn turn) {
            turns.add(turn);
        }

        @Override
        public ModelTurn stream(
                ModelRequest request,
                java.util.Set<String> allowedToolNames,
                Consumer<String> deltaConsumer,
                Duration timeout
        ) {
            requests.add(request);
            ModelTurn turn = turns.remove();
            if (turn.content() != null && !turn.content().isEmpty()) {
                deltaConsumer.accept(turn.content());
            }
            return turn;
        }
    }

    private static final class CapturingSink implements AgentStreamSink {

        private final List<String> events = new ArrayList<>();

        @Override
        public void toolStarted(String toolCallId, String toolName) {
            events.add("tool-started:" + toolCallId + ":" + toolName);
        }

        @Override
        public void toolCompleted(String toolCallId, String toolName, boolean success, long durationMs) {
            events.add("tool-completed:" + toolCallId + ":" + toolName + ":" + success);
        }

        @Override
        public void messageDelta(String content) {
            events.add("delta:" + content);
        }

        @Override
        public void confirm(String content, Map<String, Object> route) {
            events.add("confirm:" + content);
        }
    }

    private static final class SequenceNanoTime implements LongSupplier {

        private final long[] values;
        private final AtomicInteger index = new AtomicInteger();

        private SequenceNanoTime(long... values) {
            this.values = values;
        }

        @Override
        public long getAsLong() {
            int current = Math.min(index.getAndIncrement(), values.length - 1);
            return values[current];
        }
    }
}
