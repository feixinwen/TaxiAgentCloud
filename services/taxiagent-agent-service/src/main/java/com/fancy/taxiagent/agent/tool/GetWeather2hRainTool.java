package com.fancy.taxiagent.agent.tool;

import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.qweather.QweatherGateway;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * 未来两小时降水预报工具：解析经纬度参数并委托 {@link QweatherGateway#minutely5m(String)}。
 */
@Service
public class GetWeather2hRainTool implements AgentTool {

    private final QweatherGateway gateway;
    private final ObjectMapper objectMapper;

    /**
     * 构造未来两小时降水工具。
     *
     * @param gateway 和风天气网关
     * @param objectMapper JSON 参数解析器
     */
    public GetWeather2hRainTool(QweatherGateway gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "getWeather2hRain";
    }

    @Override
    public String description() {
        return "查询指定经纬度未来 2 小时逐分钟降水预报（是否下雨/降水强度）。参数：location 为\"经度,纬度\"字符串。";
    }

    @Override
    public Class<?> inputType() {
        return Arguments.class;
    }

    @Override
    public String execute(String jsonArgs, AgentExecutionContext context, AgentStreamSink sink) {
        try {
            Arguments arguments = objectMapper.readValue(jsonArgs, Arguments.class);
            if (arguments.location() == null || arguments.location().isBlank()) {
                return "Tool execution failed: location 不能为空";
            }
            return gateway.minutely5m(QweatherGateway.round(arguments.location()));
        } catch (Exception exception) {
            return "Tool execution failed: " + exception.getMessage();
        }
    }

    /** 未来两小时降水工具参数。 */
    public record Arguments(String location) {
    }
}
