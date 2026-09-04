package com.fancy.taxiagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.amap.AmapGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * POI 搜索工具：keyword/city/number 参数解析、number 默认与 gateway 透传。
 */
class PoiSearchToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AmapGateway gateway = mock(AmapGateway.class);
    private final PoiSearchTool tool = new PoiSearchTool(gateway, objectMapper);

    @Test
    void shouldParseAllArgumentsAndDelegateToGateway() {
        when(gateway.searchPoi("咖啡", "shanghai", 3)).thenReturn("1. 咖啡店（上海市黄浦区），121.48,31.23");

        String result = tool.execute("{\"keyword\":\"咖啡\",\"city\":\"shanghai\",\"number\":3}", context(), null);

        assertThat(result).isEqualTo("1. 咖啡店（上海市黄浦区），121.48,31.23");
        verify(gateway).searchPoi("咖啡", "shanghai", 3);
    }

    @Test
    void shouldPassNullCityWhenCityMissing() {
        when(gateway.searchPoi("咖啡", null, null)).thenReturn("1. 咖啡店（上海市黄浦区），121.48,31.23");

        String result = tool.execute("{\"keyword\":\"咖啡\"}", context(), null);

        assertThat(result).isEqualTo("1. 咖啡店（上海市黄浦区），121.48,31.23");
        verify(gateway).searchPoi("咖啡", null, null);
    }

    @Test
    void shouldDefaultNumberToFiveWhenNumberMissingOrNonPositive() {
        when(gateway.searchPoi("咖啡", null, null)).thenReturn("1. 咖啡店（上海市黄浦区），121.48,31.23");

        assertThat(tool.execute("{\"keyword\":\"咖啡\"}", context(), null)).isEqualTo("1. 咖啡店（上海市黄浦区），121.48,31.23");
        assertThat(tool.execute("{\"keyword\":\"咖啡\",\"number\":0}", context(), null)).isEqualTo("1. 咖啡店（上海市黄浦区），121.48,31.23");
        assertThat(tool.execute("{\"keyword\":\"咖啡\",\"number\":-3}", context(), null)).isEqualTo("1. 咖啡店（上海市黄浦区），121.48,31.23");
        verify(gateway, org.mockito.Mockito.times(3)).searchPoi("咖啡", null, null);
    }

    @Test
    void shouldPassThroughUnavailableText() {
        when(gateway.searchPoi("咖啡", null, null)).thenReturn("地点搜索暂不可用");

        String result = tool.execute("{\"keyword\":\"咖啡\"}", context(), null);

        assertThat(result).isEqualTo("地点搜索暂不可用");
    }

    @Test
    void shouldRejectBlankKeyword() {
        String result = tool.execute("{\"keyword\":\"\"}", context(), null);

        assertThat(result).startsWith("Tool execution failed:");
    }

    @Test
    void shouldRejectMissingKeyword() {
        String result = tool.execute("{}", context(), null);

        assertThat(result).startsWith("Tool execution failed:");
    }

    @Test
    void shouldReturnToolFailureOnMalformedJson() {
        String result = tool.execute("not-json", context(), null);

        assertThat(result).startsWith("Tool execution failed:");
    }

    private AgentExecutionContext context() {
        return new AgentExecutionContext(90001L, "conv-1", "run-1", "Bearer t", "trace-1", "帮我搜咖啡店", List.of());
    }
}
