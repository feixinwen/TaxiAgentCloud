package com.fancy.taxiagent.agent.agent;

import com.fancy.taxiagent.agent.client.OrderServiceFeignClient;
import com.fancy.taxiagent.agent.client.UserServiceFeignClient;
import com.fancy.taxiagent.agent.client.dto.OrderEstimateRequest;
import com.fancy.taxiagent.agent.client.dto.PoiView;
import com.fancy.taxiagent.agent.client.dto.PriceEstimateView;
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
import com.fancy.taxiagent.agent.qweather.QweatherGateway;
import com.fancy.taxiagent.agent.state.OrderStateService;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import com.fancy.taxiagent.agent.tool.GetWeatherNowTool;
import com.fancy.taxiagent.agent.tool.OrderEstimateTool;
import com.fancy.taxiagent.agent.tool.ToolRegistry;
import com.fancy.taxiagent.agent.tool.UserPoiDetailTool;
import com.fancy.taxiagent.agent.tool.UserPoiTool;
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
 * 验证 DailyAgent 委托共享 AgentLoop 后的工具白名单与循环行为。
 *
 * <p>以真实 AgentLoop + ToolRegistry 驱动，工具执行器使用 mock 下游客户端。</p>
 */
class DailyAgentTest {

    private static final String AUTHORIZATION = "Bearer private-token";
    private static final String TRACE_ID = "trace-daily-001";
    private static final AgentExecutionContext CONTEXT = new AgentExecutionContext(
            50001L,
            "550e8400-e29b-41d4-a716-446655440000",
            "run-daily-001",
            AUTHORIZATION,
            TRACE_ID,
            "从家到机场多少钱",
            List.of()
    );

    @Test
    void shouldStreamPlainTextAndReturnFinalAnswerWithoutTools() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(ModelTurn.assistant("当前位置天气未知，请使用地图 App 查询。", List.of()));
        CapturingSink sink = new CapturingSink();
        DailyAgent agent = agent(gateway, mock(OrderServiceFeignClient.class), mock(UserServiceFeignClient.class), mock(QweatherGateway.class));

        SupportAgentResult result = agent.execute(CONTEXT, sink);

        assertThat(result.content()).isEqualTo("当前位置天气未知，请使用地图 App 查询。");
        assertThat(result.modelRounds()).isEqualTo(1);
        assertThat(result.toolCalls()).isZero();
        assertThat(result.promptVersion()).isEqualTo("support-v1");
        assertThat(sink.events).containsExactly("delta:当前位置天气未知，请使用地图 App 查询。");
        assertThat(gateway.requests).singleElement().satisfies(request -> {
            assertThat(request.systemPrompt()).contains("estimatePrice", "listUserPOIs");
            assertThat(request.turns()).containsExactly(ModelTurn.user("从家到机场多少钱"));
        });
    }

    @Test
    void shouldExecuteEstimatePriceToolThenContinueModelWithToolResult() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(ModelTurn.assistant("", List.of(new ModelToolCall(
                "call-est", "estimatePrice",
                "{\"startLat\":39.9,\"startLng\":116.4,\"endLat\":40.1,\"endLng\":116.6,\"vehicleType\":1,\"isExpedited\":0}"))));
        gateway.enqueue(ModelTurn.assistant("预计费用约 30 元。", List.of()));
        OrderServiceFeignClient orderClient = mock(OrderServiceFeignClient.class);
        when(orderClient.estimate(
                org.mockito.ArgumentMatchers.eq(AUTHORIZATION),
                org.mockito.ArgumentMatchers.eq(TRACE_ID),
                org.mockito.ArgumentMatchers.any(OrderEstimateRequest.class)))
                .thenReturn(new PriceEstimateView(
                        "est-trace-1", new BigDecimal("18.30"),
                        new BigDecimal("30.00"), new BigDecimal("10.00"),
                        new BigDecimal("12.00"), new BigDecimal("3.00"),
                        new BigDecimal("15.00"), new BigDecimal("0.00")));
        CapturingSink sink = new CapturingSink();

        SupportAgentResult result = agent(gateway, orderClient, mock(UserServiceFeignClient.class), mock(QweatherGateway.class))
                .execute(CONTEXT, sink);

        assertThat(result.content()).isEqualTo("预计费用约 30 元。");
        assertThat(result.modelRounds()).isEqualTo(2);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(sink.events).containsExactly(
                "tool-started:call-est:estimatePrice",
                "tool-completed:call-est:estimatePrice:true",
                "delta:预计费用约 30 元。"
        );
        verify(orderClient).estimate(
                org.mockito.ArgumentMatchers.eq(AUTHORIZATION),
                org.mockito.ArgumentMatchers.eq(TRACE_ID),
                org.mockito.ArgumentMatchers.any(OrderEstimateRequest.class));
        assertThat(gateway.requests.get(1).turns().get(2).toolResults()).singleElement().satisfies(toolResult -> {
            assertThat(toolResult.toolCallId()).isEqualTo("call-est");
            assertThat(toolResult.content()).contains("30.00");
        });
    }

    @Test
    void shouldExecuteListUserPoisToolThenContinueModelWithToolResult() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(ModelTurn.assistant("", List.of(
                new ModelToolCall("call-poi", "listUserPOIs", "{}"))));
        gateway.enqueue(ModelTurn.assistant("您的常用地点：家、公司。", List.of()));
        UserServiceFeignClient userClient = mock(UserServiceFeignClient.class);
        when(userClient.listPois(AUTHORIZATION, TRACE_ID)).thenReturn(List.of(
                new PoiView(1L, "家", "幸福小区", "幸福路 1 号",
                        new BigDecimal("116.40"), new BigDecimal("39.90"), null),
                new PoiView(2L, "公司", "科技大厦", "科技路 2 号",
                        new BigDecimal("116.60"), new BigDecimal("40.10"), null)));
        CapturingSink sink = new CapturingSink();

        SupportAgentResult result = agent(gateway, mock(OrderServiceFeignClient.class), userClient, mock(QweatherGateway.class))
                .execute(CONTEXT, sink);

        assertThat(result.content()).isEqualTo("您的常用地点：家、公司。");
        assertThat(result.modelRounds()).isEqualTo(2);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(sink.events).containsExactly(
                "tool-started:call-poi:listUserPOIs",
                "tool-completed:call-poi:listUserPOIs:true",
                "delta:您的常用地点：家、公司。"
        );
        verify(userClient).listPois(AUTHORIZATION, TRACE_ID);
    }

    @Test
    void shouldRejectToolOutsideDailyWhitelist() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(ModelTurn.assistant("", List.of(
                new ModelToolCall("call-rag", "searchKnowledgeBase", "{\"query\":\"规则\"}"))));

        assertThatThrownBy(() -> agent(
                gateway, mock(OrderServiceFeignClient.class), mock(UserServiceFeignClient.class),
                mock(QweatherGateway.class))
                .execute(CONTEXT, new CapturingSink()))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("TOOL_NOT_ALLOWED");
    }

    @Test
    void shouldExecuteGetUserPoiDetailToolThenContinueModelWithToolResult() {
        FakeGateway gateway = new FakeGateway();
        gateway.enqueue(ModelTurn.assistant("", List.of(
                new ModelToolCall("call-detail", "getUserPOIDetail", "{\"id\":1}"))));
        gateway.enqueue(ModelTurn.assistant("家：幸福小区，幸福路 1 号。", List.of()));
        UserServiceFeignClient userClient = mock(UserServiceFeignClient.class);
        when(userClient.getPoi(AUTHORIZATION, TRACE_ID, 1L)).thenReturn(new PoiView(
                1L, "家", "幸福小区", "幸福路 1 号",
                new BigDecimal("116.40"), new BigDecimal("39.90"), null));
        CapturingSink sink = new CapturingSink();

        SupportAgentResult result = agent(gateway, mock(OrderServiceFeignClient.class), userClient, mock(QweatherGateway.class))
                .execute(CONTEXT, sink);

        assertThat(result.content()).isEqualTo("家：幸福小区，幸福路 1 号。");
        assertThat(result.modelRounds()).isEqualTo(2);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(sink.events).containsExactly(
                "tool-started:call-detail:getUserPOIDetail",
                "tool-completed:call-detail:getUserPOIDetail:true",
                "delta:家：幸福小区，幸福路 1 号。"
        );
        verify(userClient).getPoi(AUTHORIZATION, TRACE_ID, 1L);
    }

    @Test
    void shouldExecuteGetWeatherNowToolThenContinueModelWithToolResult() {
        FakeGateway modelGateway = new FakeGateway();
        modelGateway.enqueue(ModelTurn.assistant("", List.of(new ModelToolCall(
                "call-wx", "getWeatherNow",
                "{\"location\":\"116.40,39.90\"}"))));
        modelGateway.enqueue(ModelTurn.assistant("当前天气：多云，21℃，体感 19℃。", List.of()));
        QweatherGateway qweatherGateway = mock(QweatherGateway.class);
        when(qweatherGateway.now("116.40,39.90"))
                .thenReturn("当前天气：多云，21℃，体感 19℃；东南风 2 级风；湿度 60%。");
        CapturingSink sink = new CapturingSink();

        SupportAgentResult result = agent(modelGateway,
                mock(OrderServiceFeignClient.class), mock(UserServiceFeignClient.class), qweatherGateway)
                .execute(CONTEXT, sink);

        assertThat(result.content()).isEqualTo("当前天气：多云，21℃，体感 19℃。");
        assertThat(result.modelRounds()).isEqualTo(2);
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(sink.events).containsExactly(
                "tool-started:call-wx:getWeatherNow",
                "tool-completed:call-wx:getWeatherNow:true",
                "delta:当前天气：多云，21℃，体感 19℃。"
        );
        verify(qweatherGateway).now("116.40,39.90");
    }

    private DailyAgent agent(AgentModelGateway gateway,
                             OrderServiceFeignClient orderClient,
                             UserServiceFeignClient userClient,
                             QweatherGateway qweatherGateway) {
        AgentExecutionProperties executionProperties = new AgentExecutionProperties();
        AgentModelProperties modelProperties = new AgentModelProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                new OrderEstimateTool(orderClient, objectMapper, mock(OrderStateService.class)),
                new UserPoiTool(userClient, objectMapper),
                new UserPoiDetailTool(userClient, objectMapper),
                new GetWeatherNowTool(qweatherGateway, objectMapper)));
        AgentLoop agentLoop = new AgentLoop(
                gateway, executionProperties, modelProperties, toolRegistry, System::nanoTime);
        return new DailyAgent(agentLoop);
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
