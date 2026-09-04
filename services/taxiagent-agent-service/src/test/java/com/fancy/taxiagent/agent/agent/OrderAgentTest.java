package com.fancy.taxiagent.agent.agent;

import com.fancy.taxiagent.agent.client.OrderServiceFeignClient;
import com.fancy.taxiagent.agent.client.dto.OrderEstimateRequest;
import com.fancy.taxiagent.agent.client.dto.PriceEstimateView;
import com.fancy.taxiagent.agent.config.AgentExecutionProperties;
import com.fancy.taxiagent.agent.config.AgentModelProperties;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.domain.model.SupportAgentResult;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.loop.AgentLoop;
import com.fancy.taxiagent.agent.model.AgentModelGateway;
import com.fancy.taxiagent.agent.state.OrderStateService;
import com.fancy.taxiagent.agent.model.ModelRequest;
import com.fancy.taxiagent.agent.model.ModelToolCall;
import com.fancy.taxiagent.agent.model.ModelTurn;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import com.fancy.taxiagent.agent.tool.OrderEstimateTool;
import com.fancy.taxiagent.agent.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 OrderAgent 委托共享 AgentLoop 后的工具白名单与循环行为。
 */
class OrderAgentTest {

    private static final String AUTHORIZATION = "Bearer private-token";
    private static final String TRACE_ID = "trace-order-001";
    private static final AgentExecutionContext CONTEXT = new AgentExecutionContext(
            50001L,
            "550e8400-e29b-41d4-a716-446655440000",
            "run-order-001",
            AUTHORIZATION,
            TRACE_ID,
            "我要打车去机场",
            List.of()
    );

    @Test
    void shouldStreamPlainTextAndReturnFinalAnswerWithoutTools() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(ModelTurn.assistant("好的，请提供您的起点和终点。", List.of()));
        CapturingSink sink = new CapturingSink();
        OrderAgent agent = agent(gateway, mock(OrderServiceFeignClient.class));

        SupportAgentResult result = agent.execute(CONTEXT, sink);

        assertThat(result.content()).isEqualTo("好的，请提供您的起点和终点。");
        assertThat(result.modelRounds()).isEqualTo(1);
        assertThat(result.toolCalls()).isZero();
        assertThat(result.promptVersion()).isEqualTo("support-v1");
        assertThat(sink.events).containsExactly("delta:好的，请提供您的起点和终点。");
        assertThat(gateway.requests).singleElement().satisfies(request -> {
            assertThat(request.systemPrompt()).contains("saveOrderParam", "confirmOrder", "createOrder", "不得请求")
                    .contains("getUserLocation", "快车、立即出发、不加急")
                    .contains("当前时间是：");
            assertThat(request.turns()).containsExactly(ModelTurn.user("我要打车去机场"));
        });
    }

    @Test
    void shouldExecuteEstimatePriceToolThenContinueModelWithToolResult() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(ModelTurn.assistant("", List.of(new ModelToolCall(
                "call-est", "estimatePrice",
                "{\"startLat\":39.9,\"startLng\":116.4,\"endLat\":40.1,\"endLng\":116.6,\"vehicleType\":2,\"isExpedited\":0}"))));
        gateway.enqueue(ModelTurn.assistant("优享预估约 45 元。", List.of()));
        OrderServiceFeignClient orderClient = mock(OrderServiceFeignClient.class);
        when(orderClient.estimate(
                org.mockito.ArgumentMatchers.eq(AUTHORIZATION),
                org.mockito.ArgumentMatchers.eq(TRACE_ID),
                org.mockito.ArgumentMatchers.any(OrderEstimateRequest.class)))
                .thenReturn(new PriceEstimateView(
                        "est-trace-1", new BigDecimal("27.50"),
                        new BigDecimal("45.00"), new BigDecimal("15.00"),
                        new BigDecimal("18.00"), new BigDecimal("4.00"),
                        new BigDecimal("23.00"), new BigDecimal("0.00")));
        CapturingSink sink = new CapturingSink();

        SupportAgentResult result = agent(gateway, orderClient).execute(CONTEXT, sink);

        assertThat(result.content()).isEqualTo("优享预估约 45 元。");
        assertThat(result.modelRounds()).isEqualTo(2);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(sink.events).containsExactly(
                "tool-started:call-est:estimatePrice",
                "tool-completed:call-est:estimatePrice:true",
                "delta:优享预估约 45 元。"
        );
        verify(orderClient).estimate(
                org.mockito.ArgumentMatchers.eq(AUTHORIZATION),
                org.mockito.ArgumentMatchers.eq(TRACE_ID),
                org.mockito.ArgumentMatchers.any(OrderEstimateRequest.class));
    }

    @Test
    void shouldRejectToolOutsideOrderWhitelist() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(ModelTurn.assistant("", List.of(
                new ModelToolCall("call-poi", "listUserPOIs", "{}"))));

        assertThatThrownBy(() -> agent(gateway, mock(OrderServiceFeignClient.class))
                .execute(CONTEXT, new CapturingSink()))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("TOOL_NOT_ALLOWED");
    }

    @Test
    void shouldRejectWeatherToolOutsideOrderWhitelist() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(ModelTurn.assistant("", List.of(
                new ModelToolCall("call-wx", "getWeatherNow", "{\"location\":\"116.40,39.90\"}"))));

        assertThatThrownBy(() -> agent(gateway, mock(OrderServiceFeignClient.class))
                .execute(CONTEXT, new CapturingSink()))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("TOOL_NOT_ALLOWED");
    }

    private OrderAgent agent(AgentModelGateway gateway, OrderServiceFeignClient orderClient) {
        AgentExecutionProperties executionProperties = new AgentExecutionProperties();
        AgentModelProperties modelProperties = new AgentModelProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                new OrderEstimateTool(orderClient, objectMapper, mock(OrderStateService.class))));
        AgentLoop agentLoop = new AgentLoop(
                gateway, executionProperties, modelProperties, toolRegistry, System::nanoTime);
        return new OrderAgent(agentLoop);
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
}
