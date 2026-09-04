package com.fancy.taxiagent.agent.tool;

import com.fancy.taxiagent.agent.amap.AmapGateway;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * POI 地点搜索工具：解析关键词与可选城市/数量并委托
 * {@link AmapGateway#searchPoi(String, String, Integer)}（照单体 POISearchTool 的高德语义）。
 */
@Service
public class PoiSearchTool implements AgentTool {

    private final AmapGateway gateway;
    private final ObjectMapper objectMapper;

    /**
     * 构造 POI 搜索工具。
     *
     * @param gateway 高德 POI 网关
     * @param objectMapper JSON 参数解析器
     */
    public PoiSearchTool(AmapGateway gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "searchPOIs";
    }

    @Override
    public String description() {
        return "搜索指定关键词的地点候选（POI），返回名称/地址/经纬度列表。参数：keyword 为搜索关键词（必填，"
                + "支持文字名称如\"火车站\"）；city 为城市名（可选，用于限定搜索城市）；"
                + "number 为返回数量（可选，默认 5）。";
    }

    @Override
    public Class<?> inputType() {
        return Arguments.class;
    }

    @Override
    public String execute(String jsonArgs, AgentExecutionContext context, AgentStreamSink sink) {
        try {
            Arguments arguments = objectMapper.readValue(jsonArgs, Arguments.class);
            if (arguments.keyword() == null || arguments.keyword().isBlank()) {
                return "Tool execution failed: keyword 不能为空";
            }
            Integer number = (arguments.number() == null || arguments.number() <= 0) ? null : arguments.number();
            return gateway.searchPoi(arguments.keyword(), arguments.city(), number);
        } catch (Exception exception) {
            return "Tool execution failed: " + exception.getMessage();
        }
    }

    /** POI 搜索工具参数。 */
    public record Arguments(String keyword, String city, Integer number) {
    }
}
