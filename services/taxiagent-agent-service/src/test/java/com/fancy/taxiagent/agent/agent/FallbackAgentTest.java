package com.fancy.taxiagent.agent.agent;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import com.fancy.taxiagent.agent.tool.ToolRegistry;
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

/**
 * 验证 FallbackAgent 委托共享 AgentLoop 后的无工具纯文本流式语义。
 *
 * <p>循环语义（流式模型调用、空工具白名单检查、截止时间控制）由 AgentLoop 提供；
 * 本测试以真实 AgentLoop 驱动，同时作为 AgentLoop 空白名单路径的行为测试。</p>
 */
class FallbackAgentTest {

    private static final String TRACE_ID = "trace-fallback-001";
    private static final AgentExecutionContext CONTEXT = new AgentExecutionContext(
            50001L,
            "550e8400-e29b-41d4-a716-446655440000",
            "run-fallback-001",
            "Bearer private-token",
            TRACE_ID,
            "讲个笑话",
            List.of(ModelTurn.user("上一句用户问题"), ModelTurn.assistant("上一句助手答复", List.of()))
    );

    @Test
    void shouldStreamPlainTextAndReturnSingleRoundWithoutTools() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(ModelTurn.assistant("我专注出行服务，这个笑话我讲不了。", List.of()));
        CapturingSink sink = new CapturingSink();

        SupportAgentResult result = agent(gateway, System::nanoTime).execute(CONTEXT, sink);

        assertThat(result.content()).isEqualTo("我专注出行服务，这个笑话我讲不了。");
        assertThat(result.modelRounds()).isEqualTo(1);
        assertThat(result.toolCalls()).isZero();
        assertThat(result.promptVersion()).isEqualTo("support-v1");
        assertThat(sink.events).containsExactly("delta:我专注出行服务，这个笑话我讲不了。");
        assertThat(gateway.requests).singleElement().satisfies(request -> {
            assertThat(request.systemPrompt()).contains("当前时间是：", "只专注出行服务");
            assertThat(request.turns()).containsExactly(
                    ModelTurn.user("上一句用户问题"),
                    ModelTurn.assistant("上一句助手答复", List.of()),
                    ModelTurn.user("讲个笑话"));
        });
    }

    @Test
    void shouldRejectAnyToolCallWithEmptyWhitelist() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(ModelTurn.assistant("", List.of(
                new ModelToolCall("call-evil", "estimatePrice", "{}"))));
        CapturingSink sink = new CapturingSink();

        assertThatThrownBy(() -> agent(gateway, System::nanoTime).execute(CONTEXT, sink))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("TOOL_NOT_ALLOWED");
        assertThat(sink.events).isEmpty();
    }

    @Test
    void shouldThrowRunTimeoutWhenDeadlineIsExceeded() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(ModelTurn.assistant("迟到的回答", List.of()));

        assertThatThrownBy(() -> agent(gateway, new SequenceNanoTime(
                0L, 0L, 0L, 0L, Duration.ofSeconds(61).toNanos(), Duration.ofSeconds(61).toNanos()))
                .execute(CONTEXT, new CapturingSink()))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("RUN_TIMEOUT");
    }

    @Test
    void shouldPropagateModelFailureAndLogStableCode() {
        AgentModelGateway gateway = (request, allowedToolNames, deltaConsumer, timeout) -> {
            throw new AgentExecutionException("MODEL_UNAVAILABLE", "model unavailable", null);
        };
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AgentLoop.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> agent(gateway, System::nanoTime)
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

    private FallbackAgent agent(AgentModelGateway gateway, LongSupplier nanoTime) {
        AgentExecutionProperties executionProperties = new AgentExecutionProperties();
        AgentModelProperties modelProperties = new AgentModelProperties();
        ToolRegistry toolRegistry = new ToolRegistry(List.of());
        AgentLoop agentLoop = new AgentLoop(
                gateway, executionProperties, modelProperties, toolRegistry, nanoTime);
        return new FallbackAgent(agentLoop);
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
