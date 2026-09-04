package com.fancy.taxiagent.order.amap.service;

import com.fancy.taxiagent.order.amap.config.AmapProperties;
import com.fancy.taxiagent.order.amap.pojo.georegeo.AmapRegeoResponse;
import com.fancy.taxiagent.order.amap.pojo.georegeo.Regeocode;
import com.fancy.taxiagent.order.exception.AmapErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

/**
 * 高德逆地理编码（经纬度 → 格式化地址，用于订单地址补全）。
 *
 * <p>业务失败返回 empty（不阻断下单），HTTP/网络异常抛 AmapErrorException。</p>
 */
@Service
public class AmapGeoRegeoService {

    private static final Logger log = LoggerFactory.getLogger(AmapGeoRegeoService.class);

    private final RestClient amapRestClient;
    private final AmapProperties properties;

    public AmapGeoRegeoService(@Qualifier("amapRestClient") RestClient amapRestClient,
                               AmapProperties properties) {
        this.amapRestClient = amapRestClient;
        this.properties = properties;
    }

    public Optional<Regeocode> getRegeo(double longitude, double latitude) {
        if (properties.getKey() == null || properties.getKey().isBlank()) {
            throw new AmapErrorException("amap.key 未配置");
        }
        String locationParam = String.format("%.6f,%.6f", longitude, latitude);
        AmapRegeoResponse response;
        try {
            response = amapRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/geocode/regeo")
                            .queryParam("location", locationParam)
                            .queryParam("key", properties.getKey())
                            .queryParam("radius", 1000)
                            .queryParam("extensions", "base")
                            .queryParam("output", "json")
                            .queryParam("roadlevel", 0)
                            .build())
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, resp) -> {
                                throw new AmapErrorException("高德 API HTTP 请求失败: "
                                        + resp.getStatusCode() + " " + resp.getStatusText());
                            })
                    .body(AmapRegeoResponse.class);
        } catch (RestClientException e) {
            throw new AmapErrorException("高德 API 调用异常", e);
        }
        if (response != null && response.isSuccess()) {
            return Optional.ofNullable(response.regeocode());
        }
        log.warn("高德 API 业务失败: info={}, infocode={}",
                response != null ? response.info() : "null",
                response != null ? response.infocode() : "null");
        return Optional.empty();
    }
}
