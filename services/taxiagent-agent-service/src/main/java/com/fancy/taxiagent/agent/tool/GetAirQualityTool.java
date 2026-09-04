package com.fancy.taxiagent.agent.tool;

import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.qweather.QweatherGateway;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * 空气质量查询工具：解析经纬度参数并委托 {@link QweatherGateway#air(String)}。
 */
@Service
public class GetAirQualityTool implements AgentTool {

    private final QweatherGateway gateway;
    private final ObjectMapper objectMapper;

    /**
     * 构造空气质量工具。
     *
     * @param gateway 和风天气网关
     * @param objectMapper JSON 参数解析器
     */
    public GetAirQualityTool(QweatherGateway gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "getAirQuality";
    }

    @Override
    public String description() {
        return "查询指定经纬度空气质量（AQI/类别/首要污染物/PM2.5/PM10）。参数：location 为\"经度,纬度\"字符串。";
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
            return gateway.air(QweatherGateway.round(arguments.location()));
        } catch (Exception exception) {
            return "Tool execution failed: " + exception.getMessage();
        }
    }

    /** 空气质量工具参数。 */
    public record Arguments(String location) {
    }
}
