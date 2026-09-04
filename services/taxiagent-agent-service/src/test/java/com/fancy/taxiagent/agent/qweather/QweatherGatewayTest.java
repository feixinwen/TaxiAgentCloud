package com.fancy.taxiagent.agent.qweather;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 和风天气网关：5 个 API 的 URL/参数、keyless 降级与 4xx/5xx/超时降级。
 */
class QweatherGatewayTest {

    private static final String BASE = "https://devapi.qweather.com";

    private QweatherGateway gateway;
    private QweatherProperties properties;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        properties = new QweatherProperties();
        properties.setKey("test-key");
        gateway = new QweatherGateway(builder.build(), properties, new ObjectMapper());
    }

    @Test
    void shouldReturnUnavailableWithoutKey() {
        QweatherGateway keyless = new QweatherGateway(
                RestClient.builder().build(), new QweatherProperties(), new ObjectMapper());

        assertThat(keyless.now("121.50,31.20")).isEqualTo("天气服务暂不可用");
        assertThat(keyless.forecast3d("121.50,31.20")).isEqualTo("天气服务暂不可用");
        assertThat(keyless.minutely5m("121.50,31.20")).isEqualTo("天气服务暂不可用");
        assertThat(keyless.alert("121.50,31.20")).isEqualTo("天气服务暂不可用");
        assertThat(keyless.air("121.50,31.20")).isEqualTo("天气服务暂不可用");
    }

    @Test
    void shouldQueryNowWithKeyAndLocationParams() {
        mockServer.expect(requestTo(BASE + "/v7/weather/now?key=test-key&location=121.50,31.20"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"code":"200","now":{"temp":"21","feelsLike":"22","text":"多云","windDir":"西南风","windScale":"3","humidity":"33"}}
                        """, MediaType.APPLICATION_JSON));

        String result = gateway.now("121.50,31.20");

        assertThat(result).isEqualTo("当前天气：多云，21℃，体感 22℃；西南风3 级风；湿度 33%。");
        mockServer.verify();
    }

    @Test
    void shouldQuery3dForecast() {
        mockServer.expect(requestTo(BASE + "/v7/weather/3d?key=test-key&location=121.50,31.20"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"code":"200","daily":[
                          {"fxDate":"2026-08-26","tempMax":"30","tempMin":"22","textDay":"晴","textNight":"多云","precip":"0.0"},
                          {"fxDate":"2026-08-27","tempMax":"29","tempMin":"21","textDay":"多云","textNight":"阴","precip":"2.5"},
                          {"fxDate":"2026-08-28","tempMax":"28","tempMin":"20","textDay":"小雨","textNight":"晴","precip":"8.0"}]}
                        """, MediaType.APPLICATION_JSON));

        String result = gateway.forecast3d("121.50,31.20");

        assertThat(result).contains("未来三天天气情况：", "2026-08-26，22℃~30℃，白天晴，夜间多云，预计降水量0.0mm。");
        mockServer.verify();
    }

    @Test
    void shouldQueryMinutelyWithSummary() {
        mockServer.expect(requestTo(BASE + "/v7/minutely/5m?key=test-key&location=121.50,31.20"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"code":"200","summary":"未来两小时不会下雨，可放心出行","minutely":[{"fxTime":"2026-08-26T10:00+08:00","type":"rain","precip":"0.0"}]}
                        """, MediaType.APPLICATION_JSON));

        String result = gateway.minutely5m("121.50,31.20");

        assertThat(result).isEqualTo("未来两小时降水信息：未来两小时不会下雨，可放心出行");
        mockServer.verify();
    }

    @Test
    void shouldDescribeMinutelyPeakWhenSummaryBlank() {
        mockServer.expect(requestTo(BASE + "/v7/minutely/5m?key=test-key&location=121.50,31.20"))
                .andRespond(withSuccess("""
                        {"code":"200","summary":"","minutely":[
                          {"fxTime":"2026-08-26T10:00+08:00","type":"rain","precip":"0.0"},
                          {"fxTime":"2026-08-26T10:05+08:00","type":"rain","precip":"1.2"},
                          {"fxTime":"2026-08-26T10:10+08:00","type":"rain","precip":"0.8"}]}
                        """, MediaType.APPLICATION_JSON));

        String result = gateway.minutely5m("121.50,31.20");

        assertThat(result).isEqualTo("未来两小时降水信息：预计最大降水强度约 1.2mm/h。");
        mockServer.verify();
    }

    @Test
    void shouldQueryWeatherAlert() {
        mockServer.expect(requestTo(BASE + "/v7/warning/now?key=test-key&location=121.50,31.20"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"code":"200","warning":[{"id":"101","title":"暴雨蓝色预警","level":"蓝色","typeName":"暴雨","text":"预计未来6小时降雨量将达50毫米以上"}]}
                        """, MediaType.APPLICATION_JSON));

        String result = gateway.alert("121.50,31.20");

        assertThat(result).contains("天气预警共 1 条", "暴雨", "蓝色", "暴雨蓝色预警", "预计未来6小时降雨量将达50毫米以上");
        mockServer.verify();
    }

    @Test
    void shouldReturnNoAlertMessageWhenNoWarnings() {
        mockServer.expect(requestTo(BASE + "/v7/warning/now?key=test-key&location=121.50,31.20"))
                .andRespond(withSuccess("""
                        {"code":"200","warning":[]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(gateway.alert("121.50,31.20")).isEqualTo("当前无生效中的天气预警");
        mockServer.verify();
    }

    @Test
    void shouldQueryAirQuality() {
        mockServer.expect(requestTo(BASE + "/v7/air/now?key=test-key&location=121.50,31.20"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"code":"200","now":{"aqi":"26","category":"优","primary":"NA","pm2p5":"5","pm10":"11"}}
                        """, MediaType.APPLICATION_JSON));

        String result = gateway.air("121.50,31.20");

        assertThat(result).isEqualTo("空气质量：优，AQI 26，首要污染物 NA；PM2.5 5μg/m³，PM10 11μg/m³。");
        mockServer.verify();
    }

    @Test
    void shouldSendTokenHeaderWhenConfigured() {
        properties.setToken("token-123");
        mockServer.expect(requestTo(BASE + "/v7/weather/now?key=test-key&location=121.50,31.20"))
                .andExpect(header("X-QW-Token", "token-123"))
                .andRespond(withSuccess("""
                        {"code":"200","now":{"temp":"21","feelsLike":"22","text":"多云","windDir":"西南风","windScale":"3","humidity":"33"}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(gateway.now("121.50,31.20")).contains("当前天气：多云");
        mockServer.verify();
    }

    @Test
    void shouldDegradeOn4xx() {
        mockServer.expect(requestTo(BASE + "/v7/weather/now?key=test-key&location=121.50,31.20"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThat(gateway.now("121.50,31.20")).isEqualTo("天气服务暂不可用");
        mockServer.verify();
    }

    @Test
    void shouldDegradeOn5xx() {
        mockServer.expect(requestTo(BASE + "/v7/weather/now?key=test-key&location=121.50,31.20"))
                .andRespond(withServerError());

        assertThat(gateway.now("121.50,31.20")).isEqualTo("天气服务暂不可用");
        mockServer.verify();
    }

    @Test
    void shouldDegradeOnTimeout() {
        mockServer.expect(requestTo(BASE + "/v7/weather/now?key=test-key&location=121.50,31.20"))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThat(gateway.now("121.50,31.20")).isEqualTo("天气服务暂不可用");
        mockServer.verify();
    }

    @Test
    void shouldDegradeOnNon200BusinessCode() {
        mockServer.expect(requestTo(BASE + "/v7/weather/now?key=test-key&location=121.50,31.20"))
                .andRespond(withSuccess("""
                        {"code":"401","now":{}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(gateway.now("121.50,31.20")).isEqualTo("天气服务暂不可用");
        mockServer.verify();
    }

    @Test
    void shouldRoundLocationToTwoDecimals() {
        assertThat(QweatherGateway.round("121.505,31.235")).isEqualTo("121.51,31.24");
        assertThat(QweatherGateway.round("121.5,31.2")).isEqualTo("121.50,31.20");
        assertThat(QweatherGateway.round(" 121.5 , 31.2 ")).isEqualTo("121.50,31.20");
        assertThat(QweatherGateway.round("abc,def")).isEqualTo("abc,def");
        assertThat(QweatherGateway.round("121.5")).isEqualTo("121.5");
        assertThat(QweatherGateway.round(null)).isNull();
    }

}
