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
 * 天气预警工具：location 参数解析、round 复用与 gateway 透传。
 */
class GetWeatherAlertToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QweatherGateway gateway = mock(QweatherGateway.class);
    private final GetWeatherAlertTool tool = new GetWeatherAlertTool(gateway, objectMapper);

    @Test
    void shouldRoundLocationAndDelegateToGateway() {
        when(gateway.alert("121.51,31.24")).thenReturn("天气预警共 1 条：暴雨蓝色预警");

        String result = tool.execute("{\"location\":\"121.505,31.235\"}", context(), null);

        assertThat(result).isEqualTo("天气预警共 1 条：暴雨蓝色预警");
        verify(gateway).alert("121.51,31.24");
    }

    @Test
    void shouldPassThroughUnchangedLocationWhenNotParseable() {
        when(gateway.alert("abc,def")).thenReturn("ok");

        String result = tool.execute("{\"location\":\"abc,def\"}", context(), null);

        assertThat(result).isEqualTo("ok");
        verify(gateway).alert("abc,def");
    }

    @Test
    void shouldRejectBlankLocation() {
        String result = tool.execute("{\"location\":\"\"}", context(), null);

        assertThat(result).startsWith("Tool execution failed:");
    }

    @Test
    void shouldReturnToolFailureOnMalformedJson() {
        String result = tool.execute("not-json", context(), null);

        assertThat(result).startsWith("Tool execution failed:");
    }

    @Test
    void shouldPassThroughUnavailableText() {
        when(gateway.alert("121.50,31.20")).thenReturn("天气服务暂不可用");

        String result = tool.execute("{\"location\":\"121.5,31.2\"}", context(), null);

        assertThat(result).isEqualTo("天气服务暂不可用");
    }

    private AgentExecutionContext context() {
        return new AgentExecutionContext(90001L, "conv-1", "run-1", "Bearer t", "trace-1", "今天有预警吗", List.of());
    }
}
