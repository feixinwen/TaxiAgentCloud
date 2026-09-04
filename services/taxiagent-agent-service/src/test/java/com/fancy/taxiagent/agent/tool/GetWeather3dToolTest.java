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
 * 未来三天预报工具：location 参数解析、round 复用与 gateway 透传。
 */
class GetWeather3dToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QweatherGateway gateway = mock(QweatherGateway.class);
    private final GetWeather3dTool tool = new GetWeather3dTool(gateway, objectMapper);

    @Test
    void shouldRoundLocationAndDelegateToGateway() {
        when(gateway.forecast3d("121.51,31.24")).thenReturn("未来三天天气情况：2026-08-26，22℃~30℃");

        String result = tool.execute("{\"location\":\"121.505,31.235\"}", context(), null);

        assertThat(result).isEqualTo("未来三天天气情况：2026-08-26，22℃~30℃");
        verify(gateway).forecast3d("121.51,31.24");
    }

    @Test
    void shouldPassThroughUnchangedLocationWhenNotParseable() {
        when(gateway.forecast3d("abc,def")).thenReturn("ok");

        String result = tool.execute("{\"location\":\"abc,def\"}", context(), null);

        assertThat(result).isEqualTo("ok");
        verify(gateway).forecast3d("abc,def");
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
        when(gateway.forecast3d("121.50,31.20")).thenReturn("天气服务暂不可用");

        String result = tool.execute("{\"location\":\"121.5,31.2\"}", context(), null);

        assertThat(result).isEqualTo("天气服务暂不可用");
    }

    private AgentExecutionContext context() {
        return new AgentExecutionContext(90001L, "conv-1", "run-1", "Bearer t", "trace-1", "明天天气怎么样", List.of());
    }
}
