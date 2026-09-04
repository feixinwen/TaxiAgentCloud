package com.fancy.taxiagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.qweather.QweatherGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 实时天气工具：location 参数解析、round 复用与 gateway 透传。
 */
class GetWeatherNowToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QweatherGateway gateway = mock(QweatherGateway.class);
    private final GetWeatherNowTool tool = new GetWeatherNowTool(gateway, objectMapper);

    @Test
    void shouldRoundLocationAndDelegateToGateway() {
        when(gateway.now("121.51,31.24")).thenReturn("当前天气：多云，21℃");

        String result = tool.execute("{\"location\":\"121.505,31.235\"}", context(), null);

        assertThat(result).isEqualTo("当前天气：多云，21℃");
        verify(gateway).now("121.51,31.24");
    }

    @Test
    void shouldPassThroughUnchangedLocationWhenNotParseable() {
        when(gateway.now("abc,def")).thenReturn("ok");

        String result = tool.execute("{\"location\":\"abc,def\"}", context(), null);

        assertThat(result).isEqualTo("ok");
        verify(gateway).now("abc,def");
    }

    @Test
    void shouldRejectBlankLocation() {
        String result = tool.execute("{\"location\":\"\"}", context(), null);

        assertThat(result).startsWith("Tool execution failed:");
    }

    @Test
    void shouldRejectMissingLocation() {
        String result = tool.execute("{}", context(), null);

        assertThat(result).startsWith("Tool execution failed:");
    }

    @Test
    void shouldReturnToolFailureOnMalformedJson() {
        String result = tool.execute("not-json", context(), null);

        assertThat(result).startsWith("Tool execution failed:");
    }

    @Test
    void shouldPassThroughUnavailableText() {
        when(gateway.now("121.50,31.20")).thenReturn("天气服务暂不可用");

        String result = tool.execute("{\"location\":\"121.5,31.2\"}", context(), null);

        assertThat(result).isEqualTo("天气服务暂不可用");
    }

    private AgentExecutionContext context() {
        return new AgentExecutionContext(90001L, "conv-1", "run-1", "Bearer t", "trace-1", "现在天气怎么样", List.of());
    }
}
