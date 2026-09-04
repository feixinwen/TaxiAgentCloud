package com.fancy.taxiagent.agent.amap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 高德开放平台客户端：POI 关键词搜索（照单体 POISearchTool 的 place/text 语义）。
 *
 * <p>无 API Key 或调用失败时返回"地点搜索暂不可用"，不抛异常（对话继续）。</p>
 */
@Service
public class AmapGateway {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;

    public AmapGateway(RestClient.Builder builder,
                       ObjectMapper objectMapper,
                       @Value("${amap.key:}") String apiKey,
                       @Value("${amap.url:https://restapi.amap.com/v3}") String baseUrl) {
        this.restClient = builder.build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    /**
     * POI 关键词搜索。
     *
     * @param keyword 搜索关键词
     * @param city 城市名（可空）
     * @param number 返回数量（可空，默认 5）
     * @return 候选地点文本；不可用返回提示
     */
    public String searchPoi(String keyword, String city, Integer number) {
        if (apiKey == null || apiKey.isBlank()) {
            return "地点搜索暂不可用";
        }
        if (keyword == null || keyword.isBlank()) {
            return "地点搜索暂不可用";
        }
        try {
            org.springframework.web.util.UriComponentsBuilder uriBuilder =
                    org.springframework.web.util.UriComponentsBuilder.fromHttpUrl(baseUrl)
                            .path("/place/text")
                            .queryParam("key", apiKey)
                            .queryParam("keywords", keyword)
                            .queryParam("offset", number == null ? 5 : number);
            if (city != null && !city.isBlank()) {
                uriBuilder.queryParam("city", city);
            }
            String body = restClient.get()
                    .uri(uriBuilder.build().encode().toUri())
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                return "地点搜索暂不可用";
            }
            JsonNode root = objectMapper.readTree(body);
            if (!"1".equals(root.path("status").asText())) {
                return "地点搜索暂不可用";
            }
            JsonNode pois = root.path("pois");
            if (!pois.isArray() || pois.isEmpty()) {
                return "未找到相关地点";
            }
            StringBuilder result = new StringBuilder();
            int count = 0;
            for (JsonNode poi : pois) {
                if (count >= (number == null ? 5 : number)) {
                    break;
                }
                if (result.length() > 0) {
                    result.append("\n");
                }
                result.append(++count).append(". ").append(poi.path("name").asText(""))
                        .append("（").append(poi.path("address").asText("地址未知")).append("），")
                        .append(poi.path("location").asText(""));
            }
            return result.toString();
        } catch (RestClientException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            return "地点搜索暂不可用";
        }
    }
}
