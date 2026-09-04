package com.fancy.taxiagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.agent.client.OrderServiceFeignClient;
import com.fancy.taxiagent.agent.client.dto.OrderEstimateRequest;
import com.fancy.taxiagent.agent.client.dto.PriceEstimateView;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.state.OrderStateService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderEstimateToolTest {

    private final OrderServiceFeignClient feignClient = mock(OrderServiceFeignClient.class);
    private final OrderStateService stateService = mock(OrderStateService.class);
    private final OrderEstimateTool tool = new OrderEstimateTool(feignClient, new ObjectMapper(), stateService);

    @Test
    void shouldEstimatePriceAndReturnJson() {
        when(feignClient.estimate(eq("Bearer t"), eq("trace-1"), any(OrderEstimateRequest.class)))
                .thenReturn(new PriceEstimateView(
                        "est-trace-1", new BigDecimal("27.50"),
                        new BigDecimal("19.60"), new BigDecimal("1.00"),
                        new BigDecimal("14.60"), new BigDecimal("5.00"),
                        BigDecimal.ZERO, BigDecimal.ZERO));

        String result = tool.execute("""
                {"startLat":31.2304,"startLng":121.4737,"endLat":31.3,"endLng":121.5,
                 "vehicleType":1,"isExpedited":0}
                """, context(), null);

        assertThat(result).contains("\"estPrice\":19.60").contains("\"traceId\":\"est-trace-1\"");
    }

    @Test
    void shouldSaveEstimateSnapshotForTraceReuse() {
        when(feignClient.estimate(any(), any(), any(OrderEstimateRequest.class)))
                .thenReturn(new PriceEstimateView(
                        "est-trace-1", new BigDecimal("27.50"),
                        new BigDecimal("19.60"), new BigDecimal("1.00"),
                        new BigDecimal("14.60"), new BigDecimal("5.00"),
                        BigDecimal.ZERO, BigDecimal.ZERO));

        tool.execute("""
                {"startLat":31.2304,"startLng":121.4737,"endLat":31.3,"endLng":121.5,
                 "vehicleType":2,"isExpedited":1}
                """, context(), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(stateService).setEstimate(eq("conv-1"), captor.capture());
        Map<String, String> snapshot = captor.getValue();
        assertThat(snapshot)
                .containsEntry("estTraceId", "est-trace-1")
                .containsEntry("estDistance", "27.50")
                .containsEntry("estPrice", "19.60")
                .containsEntry("estRadio", "1.00")
                .containsEntry("estVehicleType", "2")
                .containsEntry("estIsExpedited", "1")
                .containsEntry("estStartLat", "31.2304")
                .containsEntry("estEndLng", "121.5");
    }

    @Test
    void shouldNotSaveSnapshotWhenEstimateFails() {
        when(feignClient.estimate(any(), any(), any()))
                .thenThrow(FeignException.errorStatus("estimate", response(503)));

        assertThatThrownBy(() -> tool.execute("{}", context(), null))
                .isInstanceOf(AgentExecutionException.class);

        verify(stateService, never()).setEstimate(anyString(), any());
    }

    @Test
    void shouldMapFourHundredResponsesToOrderRequestRejected() {
        when(feignClient.estimate(any(), any(), any()))
                .thenThrow(FeignException.errorStatus("estimate", response(400)));

        assertThatThrownBy(() -> tool.execute("{}", context(), null))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("ORDER_REQUEST_REJECTED");
    }

    @Test
    void shouldMapFiveHundredResponsesToOrderUnavailable() {
        when(feignClient.estimate(any(), any(), any()))
                .thenThrow(FeignException.errorStatus("estimate", response(503)));

        assertThatThrownBy(() -> tool.execute("{}", context(), null))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("ORDER_UNAVAILABLE");
    }

    @Test
    void shouldMapRetryableFailuresToOrderUnavailable() {
        when(feignClient.estimate(any(), any(), any())).thenThrow(new RetryableException(
                0, "connect failed", Request.HttpMethod.POST, new ConnectException("refused"), (Long) null,
                request()));

        assertThatThrownBy(() -> tool.execute("{}", context(), null))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("ORDER_UNAVAILABLE");
    }

    @Test
    void shouldReturnToolFailureTextOnException() {
        when(feignClient.estimate(any(), any(), any()))
                .thenThrow(new IllegalStateException("订单服务不可用"));

        String result = tool.execute("{}", context(), null);

        assertThat(result).startsWith("Tool execution failed:");
    }

    @Test
    void shouldHandleMalformedArguments() {
        String result = tool.execute("not-json", context(), null);

        assertThat(result).startsWith("Tool execution failed:");
    }

    private AgentExecutionContext context() {
        return new AgentExecutionContext(
                90001L, "conv-1", "run-1", "Bearer t", "trace-1", "估算价格",
                List.of());
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
