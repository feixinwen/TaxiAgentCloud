package com.fancy.taxiagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.agent.client.UserServiceFeignClient;
import com.fancy.taxiagent.agent.client.dto.UserLocationView;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GetUserLocationToolTest {

    private final UserServiceFeignClient client = mock(UserServiceFeignClient.class);
    private final AgentStreamSink sink = mock(AgentStreamSink.class);
    private final GetUserLocationTool tool = new GetUserLocationTool(client, new ObjectMapper());

    @Test
    void shouldReturnLocationJsonOnSuccess() {
        when(client.getLocation("Bearer t", "trace-1")).thenReturn(
                new UserLocationView("31.2304", "121.4737", "人民广场"));

        String result = tool.execute("{}", context(), sink);

        assertThat(result)
                .contains("\"latitude\":\"31.2304\"")
                .contains("\"longitude\":\"121.4737\"")
                .contains("\"address\":\"人民广场\"");
    }

    @Test
    void shouldReturnGuidanceTextWhenLocationNotSet() {
        when(client.getLocation("Bearer t", "trace-1")).thenThrow(
                FeignException.errorStatus("getLocation", response(404)));

        String result = tool.execute("{}", context(), sink);

        assertThat(result).contains("尚未设置位置", "定位按钮", "起点地址");
    }

    @Test
    void shouldMapOtherClientErrorsToUserRequestRejected() {
        when(client.getLocation("Bearer t", "trace-1")).thenThrow(
                FeignException.errorStatus("getLocation", response(403)));

        assertThatThrownBy(() -> tool.execute("{}", context(), sink))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("USER_REQUEST_REJECTED");
    }

    @Test
    void shouldMapServerErrorsToUserUnavailable() {
        when(client.getLocation("Bearer t", "trace-1")).thenThrow(
                FeignException.errorStatus("getLocation", response(503)));

        assertThatThrownBy(() -> tool.execute("{}", context(), sink))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("USER_UNAVAILABLE");
    }

    @Test
    void shouldMapRetryableFailuresToUserUnavailable() {
        when(client.getLocation("Bearer t", "trace-1")).thenThrow(new RetryableException(
                0, "connect failed", Request.HttpMethod.GET, new ConnectException("refused"),
                (Long) null, request()));

        assertThatThrownBy(() -> tool.execute("{}", context(), sink))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("USER_UNAVAILABLE");
    }

    private AgentExecutionContext context() {
        return new AgentExecutionContext(
                90001L, "conv-1", "run-1", "Bearer t", "trace-1", "我要打车",
                List.of());
    }

    private Request request() {
        return Request.create(Request.HttpMethod.GET, "http://taxiagent-user-service/api/users/loc",
                Map.of(), new byte[0], StandardCharsets.UTF_8);
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
