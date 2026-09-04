package com.fancy.taxiagent.agent.tool;

import com.fancy.taxiagent.agent.client.OrderServiceFeignClient;
import com.fancy.taxiagent.agent.client.dto.CreateOrderRequest;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.state.OrderStateService;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateOrderToolTest {

    private final OrderStateService stateService = mock(OrderStateService.class);
    private final OrderServiceFeignClient feignClient = mock(OrderServiceFeignClient.class);
    private final CreateOrderTool tool = new CreateOrderTool(stateService, feignClient);

    @BeforeEach
    void setUp() {
        when(stateService.getEstimate("conv-1")).thenReturn(Map.of());
    }

    @Test
    void shouldCreateOrderAndPersistOrderId() {
        when(stateService.getAllSlots("conv-1")).thenReturn(fullSlots());
        when(feignClient.createOrder(eq("Bearer t"), eq("trace-1"), any(CreateOrderRequest.class)))
                .thenReturn("1001");

        String result = tool.execute("{}", context(), null);

        assertThat(result).contains("订单创建成功，订单号 1001").contains("如需下新单请创建新对话");
        verify(stateService).setOrderId("conv-1", "1001");
        verify(stateService).deleteSlots("conv-1");
        ArgumentCaptor<CreateOrderRequest> captured = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(feignClient).createOrder(eq("Bearer t"), eq("trace-1"), captured.capture());
        CreateOrderRequest request = captured.getValue();
        assertThat(request.mongoTraceId()).isNull();
        assertThat(request.estPrice()).isNull();
        assertThat(request.estDistance()).isNull();
        assertThat(request.radio()).isNull();
        assertThat(request.vehicleType()).isEqualTo(1);
        assertThat(request.isReservation()).isZero();
        assertThat(request.isExpedited()).isZero();
        assertThat(request.startAddress()).isEqualTo("人民广场");
        assertThat(request.startLat()).isEqualByComparingTo("31.2304");
        assertThat(request.endLng()).isEqualByComparingTo("121.3363");
    }

    @Test
    void shouldReuseEstimateSnapshotWhenSlotsMatch() {
        when(stateService.getAllSlots("conv-1")).thenReturn(fullSlots());
        when(stateService.getEstimate("conv-1")).thenReturn(Map.of(
                "estTraceId", "est-trace-1",
                "estDistance", "27.50",
                "estPrice", "45.00",
                "estRadio", "1.00",
                "estVehicleType", "1",
                "estIsExpedited", "0",
                "estStartLat", "31.2304",
                "estStartLng", "121.4737",
                "estEndLat", "31.1979",
                "estEndLng", "121.3363"));
        when(feignClient.createOrder(any(), any(), any())).thenReturn("1003");

        tool.execute("{}", context(), null);

        ArgumentCaptor<CreateOrderRequest> captured = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(feignClient).createOrder(eq("Bearer t"), eq("trace-1"), captured.capture());
        CreateOrderRequest request = captured.getValue();
        assertThat(request.mongoTraceId()).isEqualTo("est-trace-1");
        assertThat(request.estPrice()).isEqualByComparingTo("45.00");
        assertThat(request.estDistance()).isEqualByComparingTo("27.50");
        assertThat(request.radio()).isEqualByComparingTo("1.00");
    }

    @Test
    void shouldNotReuseEstimateSnapshotWhenEndChangedAfterEstimate() {
        when(stateService.getEstimate("conv-1")).thenReturn(Map.of(
                "estTraceId", "est-trace-1",
                "estDistance", "27.50",
                "estPrice", "45.00",
                "estRadio", "1.00",
                "estVehicleType", "1",
                "estIsExpedited", "0",
                "estStartLat", "31.2304",
                "estStartLng", "121.4737",
                "estEndLat", "31.1979",
                "estEndLng", "121.3363"));
        Map<String, String> slots = fullSlots();
        slots.put("endAddress", "浦东机场");
        slots.put("endLat", "31.1510");
        slots.put("endLng", "121.8052");
        when(stateService.getAllSlots("conv-1")).thenReturn(slots);
        when(feignClient.createOrder(any(), any(), any())).thenReturn("1004");

        tool.execute("{}", context(), null);

        ArgumentCaptor<CreateOrderRequest> captured = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(feignClient).createOrder(eq("Bearer t"), eq("trace-1"), captured.capture());
        CreateOrderRequest request = captured.getValue();
        assertThat(request.mongoTraceId()).isNull();
        assertThat(request.estPrice()).isNull();
        assertThat(request.estDistance()).isNull();
        assertThat(request.radio()).isNull();
    }

    @Test
    void shouldNotReuseEstimateSnapshotWhenVehicleTypeMismatch() {
        when(stateService.getAllSlots("conv-1")).thenReturn(fullSlots());
        when(stateService.getEstimate("conv-1")).thenReturn(Map.of(
                "estTraceId", "est-trace-1",
                "estDistance", "27.50",
                "estPrice", "45.00",
                "estRadio", "1.00",
                "estVehicleType", "2",
                "estIsExpedited", "0",
                "estStartLat", "31.2304",
                "estStartLng", "121.4737",
                "estEndLat", "31.1979",
                "estEndLng", "121.3363"));
        when(feignClient.createOrder(any(), any(), any())).thenReturn("1005");

        tool.execute("{}", context(), null);

        ArgumentCaptor<CreateOrderRequest> captured = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(feignClient).createOrder(eq("Bearer t"), eq("trace-1"), captured.capture());
        assertThat(captured.getValue().mongoTraceId()).isNull();
    }

    @Test
    void shouldParseScheduledTimeForReservation() {
        String future = LocalDateTime.now().plusHours(3)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        Map<String, String> slots = fullSlots();
        slots.put("isReservation", "1");
        slots.put("scheduledTime", future);
        when(stateService.getAllSlots("conv-1")).thenReturn(slots);
        when(feignClient.createOrder(any(), any(), any())).thenReturn("1002");

        tool.execute("{}", context(), null);

        ArgumentCaptor<CreateOrderRequest> captured = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(feignClient).createOrder(any(), any(), captured.capture());
        assertThat(captured.getValue().scheduledTime())
                .isEqualTo(LocalDateTime.parse(future, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
    }

    @Test
    void shouldRejectExpiredScheduledTimeWithoutFeignCall() {
        Map<String, String> slots = fullSlots();
        slots.put("isReservation", "1");
        slots.put("scheduledTime", "2020-01-01 09:00");
        when(stateService.getAllSlots("conv-1")).thenReturn(slots);

        String result = tool.execute("{}", context(), null);

        assertThat(result).startsWith("Tool execution failed:").contains("必须是未来的时间");
        verify(feignClient, never()).createOrder(any(), any(), any());
        verify(stateService, never()).setOrderId(any(), any());
        verify(stateService, never()).deleteSlots(any());
    }

    @Test
    void shouldRefuseWhenBreakNotCleared() {
        when(stateService.isBreak("conv-1")).thenReturn(true);

        String result = tool.execute("{}", context(), null);

        assertThat(result).isEqualTo("用户尚未确认订单，请先请用户确认后再创建。");
        verify(feignClient, never()).createOrder(any(), any(), any());
        verify(stateService, never()).setOrderId(any(), any());
    }

    @Test
    void shouldRefuseWhenOrderAlreadyCreated() {
        when(stateService.isOrderCreated("conv-1")).thenReturn(true);

        String result = tool.execute("{}", context(), null);

        assertThat(result).isEqualTo("订单已创建，如需下新单请创建新对话。");
        verify(feignClient, never()).createOrder(any(), any(), any());
        verify(stateService, never()).setOrderId(any(), any());
    }

    @Test
    void shouldListMissingSlotsWithoutFeignCall() {
        when(stateService.getAllSlots("conv-1")).thenReturn(Map.of(
                "startAddress", "人民广场", "startLat", "31.2304", "startLng", "121.4737"));

        String result = tool.execute("{}", context(), null);

        assertThat(result).isEqualTo("还缺少订单参数: vehicleType, isReservation, isExpedited, "
                + "endAddress, endLat, endLng，请补充后重试。");
        verify(feignClient, never()).createOrder(any(), any(), any());
    }

    @Test
    void shouldMapFourHundredResponsesToOrderRequestRejected() {
        when(stateService.getAllSlots("conv-1")).thenReturn(fullSlots());
        when(feignClient.createOrder(any(), any(), any()))
                .thenThrow(FeignException.errorStatus("createOrder", response(400)));

        assertThatThrownBy(() -> tool.execute("{}", context(), null))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("ORDER_REQUEST_REJECTED");
    }

    @Test
    void shouldMapFiveHundredResponsesToOrderUnavailable() {
        when(stateService.getAllSlots("conv-1")).thenReturn(fullSlots());
        when(feignClient.createOrder(any(), any(), any()))
                .thenThrow(FeignException.errorStatus("createOrder", response(503)));

        assertThatThrownBy(() -> tool.execute("{}", context(), null))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("ORDER_UNAVAILABLE");
    }

    @Test
    void shouldMapRetryableFailuresToOrderUnavailable() {
        when(stateService.getAllSlots("conv-1")).thenReturn(fullSlots());
        when(feignClient.createOrder(any(), any(), any())).thenThrow(new RetryableException(
                0, "connect failed", Request.HttpMethod.POST, new ConnectException("refused"),
                (Long) null, request()));

        assertThatThrownBy(() -> tool.execute("{}", context(), null))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("ORDER_UNAVAILABLE");
    }

    private AgentExecutionContext context() {
        return new AgentExecutionContext(
                90001L, "conv-1", "run-1", "Bearer t", "trace-1", "确认下单",
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
        return Request.create(Request.HttpMethod.POST, "http://taxiagent-order-service/api/orders",
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
