package com.fancy.taxiagent.agent.qweather;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 和风天气客户端：实时/3 天/逐分钟降水/预警/空气质量，统一返回格式化中文文本。
 *
 * <p>无 API Key 或调用失败（4xx/5xx/超时/业务码非 200）时返回"天气服务暂不可用"或"地点搜索暂不可用"，
 * 不抛异常，保证 Agent 对话在天气服务不可用时仍可继续。</p>
 */
@Service
public class QweatherGateway {

    /** 天气服务不可用的统一降级文本。 */
    public static final String UNAVAILABLE_TEXT = "天气服务暂不可用";

    private static final Logger log = LoggerFactory.getLogger(QweatherGateway.class);

    private final RestClient restClient;
    private final QweatherProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 构造和风天气网关。
     *
     * @param restClient 和风专用 RestClient（AgentHttpConfiguration 中装配，带超时与 baseUrl）
     * @param properties 和风配置
     * @param objectMapper JSON 解析器
     */
    public QweatherGateway(@Qualifier("qweatherRestClient") RestClient restClient,
                           QweatherProperties properties,
                           ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 实时天气。
     *
     * @param location "经度,纬度"
     * @return 格式化中文天气文本；不可用时返回"天气服务暂不可用"
     */
    public String now(String location) {
        JsonNode root = call("/v7/weather/now", location);
        if (root == null) {
            return UNAVAILABLE_TEXT;
        }
        JsonNode now = root.path("now");
        if (now.isMissingNode() || now.isNull()) {
            return UNAVAILABLE_TEXT;
        }
        return "当前天气：" + now.path("text").asText("未知")
                + "，" + now.path("temp").asText("?") + "℃，体感 " + now.path("feelsLike").asText("?")
                + "℃；" + now.path("windDir").asText("") + now.path("windScale").asText("") + " 级风；"
                + "湿度 " + now.path("humidity").asText("?") + "%。";
    }

    /**
     * 未来三天天气预报。
     *
     * @param location "经度,纬度"
     * @return 格式化中文预报文本；不可用时返回"天气服务暂不可用"
     */
    public String forecast3d(String location) {
        JsonNode root = call("/v7/weather/3d", location);
        if (root == null) {
            return UNAVAILABLE_TEXT;
        }
        JsonNode daily = root.path("daily");
        if (!daily.isArray() || daily.isEmpty()) {
            return UNAVAILABLE_TEXT;
        }
        StringBuilder builder = new StringBuilder("未来三天天气情况：");
        int count = 0;
        for (JsonNode day : daily) {
            if (count >= 3) {
                break;
            }
            builder.append("\n").append(day.path("fxDate").asText("?"))
                    .append("，").append(day.path("tempMin").asText("?")).append("℃~")
                    .append(day.path("tempMax").asText("?")).append("℃，白天")
                    .append(day.path("textDay").asText("?")).append("，夜间")
                    .append(day.path("textNight").asText("?")).append("，预计降水量")
                    .append(day.path("precip").asText("?")).append("mm。");
            count++;
        }
        return builder.toString();
    }

    /**
     * 未来两小时逐分钟降水预报。
     *
     * @param location "经度,纬度"
     * @return 格式化中文降水文本；不可用时返回"天气服务暂不可用"
     */
    public String minutely5m(String location) {
        JsonNode root = call("/v7/minutely/5m", location);
        if (root == null) {
            return UNAVAILABLE_TEXT;
        }
        String summary = root.path("summary").asText("");
        if (!summary.isBlank()) {
            return "未来两小时降水信息：" + summary;
        }
        JsonNode minutely = root.path("minutely");
        if (!minutely.isArray() || minutely.isEmpty()) {
            return UNAVAILABLE_TEXT;
        }
        double peak = 0.0;
        for (JsonNode node : minutely) {
            double precip = node.path("precip").asDouble(0.0);
            if (precip > peak) {
                peak = precip;
            }
        }
        if (peak > 0.0) {
            return "未来两小时降水信息：预计最大降水强度约 " + peak + "mm/h。";
        }
        return "未来两小时降水信息：预计无降水。";
    }

    /**
     * 当前生效中的天气预警。
     *
     * @param location "经度,纬度"
     * @return 格式化中文预警文本；无预警返回提示；不可用时返回"天气服务暂不可用"
     */
    public String alert(String location) {
        JsonNode root = call("/v7/warning/now", location);
        if (root == null) {
            return UNAVAILABLE_TEXT;
        }
        JsonNode warnings = root.path("warning");
        if (!warnings.isArray() || warnings.isEmpty()) {
            return "当前无生效中的天气预警";
        }
        StringBuilder builder = new StringBuilder("天气预警共 ").append(warnings.size()).append(" 条：");
        for (JsonNode warning : warnings) {
            builder.append("\n【").append(warning.path("typeName").asText(""))
                    .append(warning.path("level").asText("")).append("】")
                    .append(warning.path("title").asText("未知")).append("：")
                    .append(warning.path("text").asText(""));
        }
        return builder.toString();
    }

    /**
     * 空气质量。
     *
     * @param location "经度,纬度"
     * @return 格式化中文空气质量文本；不可用时返回"天气服务暂不可用"
     */
    public String air(String location) {
        JsonNode root = call("/v7/air/now", location);
        if (root == null) {
            return UNAVAILABLE_TEXT;
        }
        JsonNode now = root.path("now");
        if (now.isMissingNode() || now.isNull()) {
            return UNAVAILABLE_TEXT;
        }
        return "空气质量：" + now.path("category").asText("?")
                + "，AQI " + now.path("aqi").asText("?")
                + "，首要污染物 " + now.path("primary").asText("?")
                + "；PM2.5 " + now.path("pm2p5").asText("?")
                + "μg/m³，PM10 " + now.path("pm10").asText("?") + "μg/m³。";
    }

    /**
     * 经纬度字符串保留 2 位小数（四舍五入，照单体 CurWeatherTool 行为）。
     *
     * @param location "经度,纬度"
     * @return 四舍五入后的"经度,纬度"；格式非法或为空时原样返回
     */
    public static String round(String location) {
        if (location == null) {
            return null;
        }
        String[] parts = location.split(",");
        if (parts.length != 2) {
            return location;
        }
        try {
            BigDecimal lng = new BigDecimal(parts[0].trim()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal lat = new BigDecimal(parts[1].trim()).setScale(2, RoundingMode.HALF_UP);
            return lng + "," + lat;
        } catch (NumberFormatException exception) {
            return location;
        }
    }

    private JsonNode call(String path, String location) {
        if (properties.getKey() == null || properties.getKey().isBlank()) {
            return null;
        }
        try {
            RestClient.RequestHeadersSpec<?> request = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path(path)
                            .queryParam("key", properties.getKey())
                            .queryParam("location", location)
                            .build());
            if (properties.getToken() != null && !properties.getToken().isBlank()) {
                request.header("X-QW-Token", properties.getToken());
            }
            String body = request.retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                return null;
            }
            JsonNode root = objectMapper.readTree(body);
            if (!"200".equals(root.path("code").asText())) {
                log.warn("event=agent_qweather_business_failed path={} code={}", path, root.path("code").asText());
                return null;
            }
            return root;
        } catch (RestClientException | JsonProcessingException exception) {
            log.warn("event=agent_qweather_call_failed path={} error={}", path, exception.getMessage());
            return null;
        }
    }
}
