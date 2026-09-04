package com.fancy.taxiagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fancy.taxiagent.agent.client.UserServiceFeignClient;
import com.fancy.taxiagent.agent.client.dto.PoiView;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证用户兴趣点工具的参数解析、成功返回、上游错误映射与失败字符串。
 */
class UserPoiToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final UserServiceFeignClient feignClient = mock(UserServiceFeignClient.class);
    private final UserPoiTool listTool = new UserPoiTool(feignClient, objectMapper);
    private final UserPoiDetailTool detailTool = new UserPoiDetailTool(feignClient, objectMapper);

    @Test
    void shouldListPoisAndReturnJsonArray() {
        when(feignClient.listPois(eq("Bearer t"), eq("trace-1")))
                .thenReturn(List.of(new PoiView(
                        1001L, "家", "世纪花园", "浦东新区世纪大道 1 号",
                        new BigDecimal("121.5000"), new BigDecimal("31.2300"),
                        LocalDateTime.of(2026, 8, 1, 10, 0))));

        String result = listTool.execute("{}", context(), null);

        assertThat(result).contains("\"id\":1001", "\"poiTag\":\"家\"", "\"poiName\":\"世纪花园\"");
    }

    @Test
    void shouldParseIdAndReturnDetailJson() {
        when(feignClient.getPoi(eq("Bearer t"), eq("trace-1"), eq(1001L)))
                .thenReturn(new PoiView(
                        1001L, "公司", "软件大厦", "张江高科园区",
                        new BigDecimal("121.6000"), new BigDecimal("31.2000"),
                        LocalDateTime.of(2026, 8, 1, 10, 0)));

        String result = detailTool.execute("{\"id\":1001}", context(), null);

        assertThat(result).contains("\"id\":1001", "\"poiTag\":\"公司\"");
    }

    @Test
    void shouldMapFourHundredResponsesToUserRequestRejected() {
        when(feignClient.listPois(any(), any()))
                .thenThrow(FeignException.errorStatus("listPois", response(400)));

        assertThatThrownBy(() -> listTool.execute("{}", context(), null))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("USER_REQUEST_REJECTED");
    }

    @Test
    void shouldMapFiveHundredResponsesToUserUnavailable() {
        when(feignClient.getPoi(any(), any(), any()))
                .thenThrow(FeignException.errorStatus("getPoi", response(503)));

        assertThatThrownBy(() -> detailTool.execute("{\"id\":1}", context(), null))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("USER_UNAVAILABLE");
    }

    @Test
    void shouldMapRetryableFailuresToUserUnavailable() {
        when(feignClient.listPois(any(), any())).thenThrow(new RetryableException(
                0, "connect failed", Request.HttpMethod.GET, new ConnectException("refused"), (Long) null,
                request()));

        assertThatThrownBy(() -> listTool.execute("{}", context(), null))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("USER_UNAVAILABLE");
    }

    @Test
    void shouldReturnToolFailureTextOnClientError() {
        when(feignClient.listPois(any(), any()))
                .thenThrow(new IllegalStateException("用户服务不可用"));

        String result = listTool.execute("{}", context(), null);

        assertThat(result).startsWith("Tool execution failed:");
    }

    @Test
    void shouldReturnToolFailureTextOnMalformedDetailArguments() {
        String result = detailTool.execute("not-json", context(), null);

        assertThat(result).startsWith("Tool execution failed:");
    }

    private AgentExecutionContext context() {
        return new AgentExecutionContext(
                90001L, "conv-1", "run-1", "Bearer t", "trace-1", "我的常用地点",
                List.of());
    }

    private Request request() {
        return Request.create(Request.HttpMethod.GET, "http://taxiagent-user-service/api/users/poi",
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
