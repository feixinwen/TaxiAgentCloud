package com.fancy.taxiagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.agent.client.OrderServiceFeignClient;
import com.fancy.taxiagent.agent.client.dto.OrderEstimateRequest;
import com.fancy.taxiagent.agent.client.dto.PriceEstimateView;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.state.OrderStateService;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfirmOrderToolTest {

    private final OrderStateService stateService = mock(OrderStateService.class);
    private final OrderServiceFeignClient feignClient = mock(OrderServiceFeignClient.class);
    private final AgentStreamSink sink = mock(AgentStreamSink.class);
    private final ConfirmOrderTool tool =
            new ConfirmOrderTool(stateService, feignClient, new ObjectMapper());

    @Test
    void shouldListMissingSlotsWithoutFeignOrConfirm() {
        when(stateService.getAllSlots("conv-1")).thenReturn(Map.of(
                "startAddress", "人民广场", "startLat", "31.2304", "startLng", "121.4737"));

        String result = tool.execute("{}", context(), sink);

        assertThat(result).isEqualTo("还缺少订单参数: endAddress, endLat, endLng，请补充后重试。");
        verify(feignClient, never()).estimate(any(), any(), any());
        verify(sink, never()).confirm(any(), any());
        verify(stateService, never()).setBreak(any(), anyBoolean());
    }

    @Test
    void shouldApplyDefaultsAndNoteThemInReturnText() {
        when(stateService.getAllSlots("conv-1")).thenReturn(Map.of(
                "startAddress", "人民广场",
                "startLat", "31.2304",
                "startLng", "121.4737",
                "endAddress", "虹桥机场",
                "endLat", "31.1979",
                "endLng", "121.3363"));
        when(feignClient.estimate(eq("Bearer t"), eq("trace-1"), any(OrderEstimateRequest.class)))
                .thenReturn(new PriceEstimateView(
                        "est-trace-1", new BigDecimal("27.50"),
                        new BigDecimal("19.60"), new BigDecimal("1.00"),
                        new BigDecimal("14.60"), new BigDecimal("5.00"),
                        BigDecimal.ZERO, BigDecimal.ZERO));

        String result = tool.execute("{}", context(), sink);

        assertThat(result).contains("已按默认值补齐（车型=快车、立即出发、不加急）");
        verify(stateService).setSlot("conv-1", "vehicleType", "1");
        verify(stateService).setSlot("conv-1", "isReservation", "0");
        verify(stateService).setSlot("conv-1", "isExpedited", "0");
        ArgumentCaptor<OrderEstimateRequest> request = ArgumentCaptor.forClass(OrderEstimateRequest.class);
        verify(feignClient).estimate(eq("Bearer t"), eq("trace-1"), request.capture());
        assertThat(request.getValue().vehicleType()).isEqualTo(1);
        assertThat(request.getValue().isExpedited()).isZero();
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(sink).confirm(summary.capture(), any());
        assertThat(summary.getValue())
                .contains("\"vehicleType\":\"快车\"")
                .contains("\"isReservation\":false")
                .contains("\"isExpedited\":false");
    }

    @Test
    void shouldRequireScheduledTimeForReservation() {
        Map<String, String> slots = fullSlots();
        slots.put("isReservation", "1");
        when(stateService.getAllSlots("conv-1")).thenReturn(slots);

        String result = tool.execute("{}", context(), sink);

        assertThat(result).isEqualTo("还缺少订单参数: scheduledTime，请补充后重试。");
        verify(feignClient, never()).estimate(any(), any(), any());
    }

    @Test
    void shouldEstimateAndEmitConfirmWithSummary() {
        when(stateService.getAllSlots("conv-1")).thenReturn(fullSlots());
        when(feignClient.estimate(eq("Bearer t"), eq("trace-1"), any(OrderEstimateRequest.class)))
                .thenReturn(new PriceEstimateView(
                        "est-trace-1", new BigDecimal("27.50"),
                        new BigDecimal("19.60"), new BigDecimal("1.00"),
                        new BigDecimal("14.60"), new BigDecimal("5.00"),
                        BigDecimal.ZERO, BigDecimal.ZERO));

        String result = tool.execute("{}", context(), sink);

        assertThat(result).contains("订单摘要已发送，请用户确认");
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> route = ArgumentCaptor.forClass(Map.class);
        verify(sink).confirm(summary.capture(), route.capture());
        assertThat(summary.getValue())
                .contains("\"startAddress\":\"人民广场\"")
                .contains("\"endAddress\":\"虹桥机场\"")
                .contains("\"vehicleType\":\"快车\"")
                .contains("\"estPrice\":19.60")
                .contains("\"estPriceBase\":14.60");
        assertThat(route.getValue())
                .containsEntry("traceId", "est-trace-1")
                .containsEntry("estDistance", new BigDecimal("27.50"))
                .containsEntry("startLat", 31.2304D)
                .containsEntry("startLng", 121.4737D)
                .containsEntry("endLat", 31.1979D)
                .containsEntry("endLng", 121.3363D);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> snapshot = ArgumentCaptor.forClass(Map.class);
        verify(stateService).setEstimate(eq("conv-1"), snapshot.capture());
        assertThat(snapshot.getValue())
                .containsEntry("estTraceId", "est-trace-1")
                .containsEntry("estDistance", "27.50")
                .containsEntry("estPrice", "19.60")
                .containsEntry("estVehicleType", "1")
                .containsEntry("estEndLng", "121.3363");
        verify(stateService).setBreak("conv-1", true);
        verify(stateService).setLastConfirmToolCallId("conv-1", "confirmOrder");
    }

    @Test
    void shouldNotReConfirmWhileBreakActive() {
        when(stateService.isBreak("conv-1")).thenReturn(true);

        String result = tool.execute("{}", context(), sink);

        assertThat(result).isEqualTo("正在等待您的确认，请回复确认下单或说明修改内容。");
        verify(feignClient, never()).estimate(any(), any(), any());
        verify(sink, never()).confirm(any(), any());
        verify(stateService, never()).setBreak(any(), anyBoolean());
    }

    @Test
    void shouldRefuseWhenOrderAlreadyCreated() {
        when(stateService.isOrderCreated("conv-1")).thenReturn(true);

        String result = tool.execute("{}", context(), sink);

        assertThat(result).isEqualTo("订单已创建，如需下新单请创建新对话。");
        verify(feignClient, never()).estimate(any(), any(), any());
        verify(sink, never()).confirm(any(), any());
    }

    @Test
    void shouldMapFourHundredResponsesToOrderRequestRejected() {
        when(stateService.getAllSlots("conv-1")).thenReturn(fullSlots());
        when(feignClient.estimate(any(), any(), any()))
                .thenThrow(FeignException.errorStatus("estimate", response(400)));

        assertThatThrownBy(() -> tool.execute("{}", context(), sink))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("ORDER_REQUEST_REJECTED");
    }

    @Test
    void shouldMapFiveHundredResponsesToOrderUnavailable() {
        when(stateService.getAllSlots("conv-1")).thenReturn(fullSlots());
        when(feignClient.estimate(any(), any(), any()))
                .thenThrow(FeignException.errorStatus("estimate", response(503)));

        assertThatThrownBy(() -> tool.execute("{}", context(), sink))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("ORDER_UNAVAILABLE");
    }

    @Test
    void shouldMapRetryableFailuresToOrderUnavailable() {
        when(stateService.getAllSlots("conv-1")).thenReturn(fullSlots());
        when(feignClient.estimate(any(), any(), any())).thenThrow(new RetryableException(
                0, "connect failed", Request.HttpMethod.POST, new ConnectException("refused"),
                (Long) null, request()));

        assertThatThrownBy(() -> tool.execute("{}", context(), sink))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("ORDER_UNAVAILABLE");
    }

    private AgentExecutionContext context() {
        return new AgentExecutionContext(
                90001L, "conv-1", "run-1", "Bearer t", "trace-1", "确认订单",
                List.of());
    }

    private Map<String, String> fullSlots() {
        return new java.util.HashMap<>(Map.of(
                "vehicleType", "1",
                "isReservation", "0",
                "isExpedited", "0",
                "startAddress", "人民广场",
                "startLat", "31.2304",
                "startLng", "121.4737",
                "endAddress", "虹桥机场",
                "endLat", "31.1979",
                "endLng", "121.3363"));
    }

    private Request request() {
        return Request.create(Request.HttpMethod.POST, "http://taxiagent-order-service/api/orders/estimate",
                java.util.Map.of(), new byte[0], StandardCharsets.UTF_8);
    }

    private Response response(int status) {
        return Response.builder()
                .status(status)
                .reason("failure")
                .request(request())
                .body("", StandardCharsets.UTF_8)
                .build();
    }
}
