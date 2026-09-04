package com.fancy.taxiagent.agent.amap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 验证高德 POI 关键词搜索的参数构造、结果解析与降级路径。
 */
class AmapGatewayTest {

    private static final String BASE = "https://restapi.amap.com/v3";

    private RestClient restClient;
    private MockRestServiceServer mockServer;
    private AmapGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        restClient = builder.build();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        gateway = new AmapGateway(builder, new ObjectMapper(), "test-key", BASE);
    }

    @Test
    void shouldSearchPoiWithKeywordCityAndNumber() {
        mockServer.expect(requestTo(BASE + "/place/text?key=test-key&keywords=%E7%81%AB%E8%BD%A6%E7%AB%99&offset=2&city=shanghai"))
                .andRespond(withSuccess("""
                        {"status":"1","pois":[{"name":"上海火车站","address":"秣陵路303号","location":"121.455,31.251"},
                                              {"name":"上海南站","address":"沪闵路","location":"121.424,31.154"}]}
                        """, MediaType.APPLICATION_JSON));

        String result = gateway.searchPoi("火车站", "shanghai", 2);

        assertThat(result).isEqualTo("1. 上海火车站（秣陵路303号），121.455,31.251\n2. 上海南站（沪闵路），121.424,31.154");
        mockServer.verify();
    }

    @Test
    void shouldLimitToDefaultFiveWithoutNumber() {
        StringBuilder pois = new StringBuilder();
        for (int i = 1; i <= 8; i++) {
            if (i > 1) {
                pois.append(",");
            }
            pois.append("{\"name\":\"地点").append(i).append("\",\"address\":\"地址").append(i)
                    .append("\",\"location\":\"").append(i).append(".1,").append(i).append(".2\"}");
        }
        mockServer.expect(requestTo(BASE + "/place/text?key=test-key&keywords=%E5%8A%A0%E6%B2%B9%E7%AB%99&offset=5"))
                .andRespond(withSuccess("{\"status\":\"1\",\"pois\":[" + pois + "]}", MediaType.APPLICATION_JSON));

        String result = gateway.searchPoi("加油站", null, null);

        assertThat(result.lines().count()).isEqualTo(5);
        assertThat(result).startsWith("1. 地点1（地址1），1.1,1.2").endsWith("5. 地点5（地址5），5.1,5.2");
        mockServer.verify();
    }

    @Test
    void shouldReturnUnavailableWhenKeywordBlankOrMissing() {
        assertThat(gateway.searchPoi("  ", "shanghai", null)).isEqualTo("地点搜索暂不可用");
        assertThat(gateway.searchPoi(null, "shanghai", null)).isEqualTo("地点搜索暂不可用");
    }

    @Test
    void shouldReturnUnavailableWithoutKey() {
        AmapGateway keyless = new AmapGateway(RestClient.builder(), new ObjectMapper(), "", BASE);

        assertThat(keyless.searchPoi("咖啡", "shanghai", null)).isEqualTo("地点搜索暂不可用");
    }

    @Test
    void shouldReturnNotFoundWhenNoResults() {
        mockServer.expect(requestTo(BASE + "/place/text?key=test-key&keywords=%E4%B8%8D%E5%AD%98%E5%9C%A8%E7%9A%84%E5%85%B3%E9%94%AE%E8%AF%8D&offset=5"))
                .andRespond(withSuccess("{\"status\":\"1\",\"pois\":[]}", MediaType.APPLICATION_JSON));

        assertThat(gateway.searchPoi("不存在的关键词", null, null)).isEqualTo("未找到相关地点");
        mockServer.verify();
    }

    @Test
    void shouldDegradeOn4xx() {
        mockServer.expect(requestTo(BASE + "/place/text?key=test-key&keywords=%E5%92%96%E5%95%A1&offset=5"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThat(gateway.searchPoi("咖啡", null, null)).isEqualTo("地点搜索暂不可用");
        mockServer.verify();
    }

    @Test
    void shouldDegradeOn5xx() {
        mockServer.expect(requestTo(BASE + "/place/text?key=test-key&keywords=%E5%92%96%E5%95%A1&offset=5"))
                .andRespond(withServerError());

        assertThat(gateway.searchPoi("咖啡", null, null)).isEqualTo("地点搜索暂不可用");
        mockServer.verify();
    }

    @Test
    void shouldDegradeOnNonSuccessBusinessStatus() {
        mockServer.expect(requestTo(BASE + "/place/text?key=test-key&keywords=%E5%92%96%E5%95%A1&offset=5"))
                .andRespond(withSuccess("{\"status\":\"0\",\"info\":\"INVALID_USER_KEY\"}", MediaType.APPLICATION_JSON));

        assertThat(gateway.searchPoi("咖啡", null, null)).isEqualTo("地点搜索暂不可用");
        mockServer.verify();
    }
}
