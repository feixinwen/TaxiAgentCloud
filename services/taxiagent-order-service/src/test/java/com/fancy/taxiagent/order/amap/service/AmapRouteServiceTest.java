package com.fancy.taxiagent.order.amap.service;

import com.fancy.taxiagent.order.amap.config.AmapProperties;
import com.fancy.taxiagent.order.domain.vo.EstRouteVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AmapRouteServiceTest {

    private AmapRouteService routeService;
    private MockRestServiceServer mockServer;

    private static final String ROUTING_URL = "https://restapi.amap.com/v3/direction/driving";

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://restapi.amap.com/v3");
        AmapProperties properties = new AmapProperties();
        properties.setKey("test-key");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        routeService = new AmapRouteService(builder.build(), properties);
    }

    @Test
    void shouldParseDrivingRouteResponse() {
        mockServer.expect(requestTo(ROUTING_URL + "?key=test-key&origin=121.473700,31.230400"
                        + "&destination=121.500000,31.300000&extensions=all&output=json"
                        + "&strategy=10&roadaggregation=false"))
                .andExpect(queryParam("key", "test-key"))
                .andExpect(queryParam("extensions", "all"))
                .andExpect(queryParam("roadaggregation", "false"))
                .andRespond(withSuccess("""
                        {
                          "status": "1",
                          "info": "OK",
                          "infocode": "10000",
                          "route": {
                            "origin": "121.473700,31.230400",
                            "destination": "121.500000,31.300000",
                            "paths": [
                              {
                                "distance": "5234",
                                "duration": "952",
                                "steps": [
                                  {"polyline": "121.473700,31.230400;121.480000,31.240000"},
                                  {"polyline": "121.480000,31.240000;121.500000,31.300000"}
                                ]
                              }
                            ]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        EstRouteVO vo = routeService.getDrivingRouteEst(121.4737, 31.2304, 121.5, 31.3);

        assertThat(vo.estKm()).isEqualTo("5.234");
        assertThat(vo.estTime()).isEqualTo("952");
        assertThat(vo.estPolyline()).isEqualTo(
                "121.473700,31.230400;121.480000,31.240000;121.480000,31.240000;121.500000,31.300000");
        assertThat(vo.estRoute()).contains("paths=[AmapPath[");
    }

    @Test
    void shouldFailAfterRetriesOnServerError() {
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/direction/driving")))
                .andRespond(withServerError());
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/direction/driving")))
                .andRespond(withServerError());
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/direction/driving")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> routeService.getDrivingRouteEst(121.4737, 31.2304, 121.5, 31.3))
                .hasMessageContaining("高德 API HTTP 请求失败");
    }

    @Test
    void shouldSucceedOnRetry() {
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/direction/driving")))
                .andRespond(withServerError());
        mockServer.expect(requestTo(org.hamcrest.Matchers.containsString("/direction/driving")))
                .andRespond(withSuccess("""
                {"status":"1","info":"OK","route":{"paths":[{"distance":"1000","duration":"120","steps":[{"polyline":"121.1,31.1;121.2,31.2"}]}]}}
                """, MediaType.APPLICATION_JSON));

        EstRouteVO vo = routeService.getDrivingRouteEst(121.1, 31.1, 121.2, 31.2);

        assertThat(vo.estKm()).isEqualTo("1.000");
    }
}
