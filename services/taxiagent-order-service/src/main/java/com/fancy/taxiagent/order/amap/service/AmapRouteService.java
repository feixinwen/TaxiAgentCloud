package com.fancy.taxiagent.order.amap.service;

import com.fancy.taxiagent.order.amap.config.AmapProperties;
import com.fancy.taxiagent.order.amap.pojo.route.AmapDrivingRouteResponse;
import com.fancy.taxiagent.order.amap.pojo.route.AmapPath;
import com.fancy.taxiagent.order.amap.pojo.route.AmapRoute;
import com.fancy.taxiagent.order.domain.vo.EstRouteVO;
import com.fancy.taxiagent.order.exception.AmapErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * 高德驾车路径规划（计价/轨迹来源）。
 *
 * <p>约定：extensions=all + roadaggregation=false；失败自动重试 3 次；estRoute 为仅含第一条 path 的整包字符串；
 * estPolyline 为所有 step.polyline 以 ";" 拼接。</p>
 */
@Service
public class AmapRouteService {

    private static final Logger log = LoggerFactory.getLogger(AmapRouteService.class);

    private final RestClient amapRestClient;
    private final AmapProperties properties;

    public AmapRouteService(@Qualifier("amapRestClient") RestClient amapRestClient,
                            AmapProperties properties) {
        this.amapRestClient = amapRestClient;
        this.properties = properties;
    }

    public EstRouteVO getDrivingRouteEst(double originLon, double originLat,
                                         double destLon, double destLat) {
        String origin = formatLngLat(originLon, originLat);
        String destination = formatLngLat(destLon, destLat);

        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                AmapDrivingRouteResponse response = fetchDrivingRouteResponse(origin, destination);
                if (response == null) {
                    throw new AmapErrorException("高德 API 响应为空");
                }
                if (!response.isSuccess()) {
                    throw new AmapErrorException("高德 API 业务失败: info=" + response.info()
                            + ", infocode=" + response.infocode());
                }
                AmapRoute route = response.route();
                if (route == null || route.paths() == null || route.paths().isEmpty()) {
                    throw new AmapErrorException("高德 API route 为空");
                }
                var firstPath = route.paths().get(0);
                String polyline = joinPathPolylines(firstPath);
                if (polyline.isBlank()) {
                    throw new AmapErrorException("高德 API polyline 为空");
                }
                String km = calcKm(firstPath);
                String time = firstPath.duration();
                AmapRoute firstOnlyRoute = new AmapRoute(
                        route.origin(), route.destination(), route.taxiCost(), List.of(firstPath));
                return new EstRouteVO(firstOnlyRoute.toString(), polyline, km, time);
            } catch (AmapErrorException e) {
                last = e;
                if (attempt < 3) {
                    log.warn("高德路径规划失败，准备重试({}/3): {}", attempt, e.getMessage());
                    continue;
                }
                throw e;
            } catch (RuntimeException e) {
                last = e;
                if (attempt < 3) {
                    log.warn("高德路径规划异常，准备重试({}/3): {}", attempt, e.getMessage());
                    continue;
                }
                throw new AmapErrorException("高德路径规划失败(重试3次仍失败)", e);
            }
        }
        throw new AmapErrorException("高德路径规划失败(重试3次仍失败)", last);
    }

    private AmapDrivingRouteResponse fetchDrivingRouteResponse(String origin, String destination) {
        if (properties.getKey() == null || properties.getKey().isBlank()) {
            throw new AmapErrorException("amap.key 未配置");
        }
        try {
            return amapRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/direction/driving")
                            .queryParam("key", properties.getKey())
                            .queryParam("origin", origin)
                            .queryParam("destination", destination)
                            .queryParam("extensions", "all")
                            .queryParam("output", "json")
                            .queryParam("strategy", 10)
                            .queryParam("roadaggregation", false)
                            .build())
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                            (req, resp) -> {
                                throw new AmapErrorException("高德 API HTTP 请求失败: "
                                        + resp.getStatusCode() + " " + resp.getStatusText());
                            })
                    .body(AmapDrivingRouteResponse.class);
        } catch (RestClientException e) {
            throw new AmapErrorException("高德 API 调用异常", e);
        }
    }

    private static String joinPathPolylines(AmapPath path) {
        List<String> polylines = new ArrayList<>();
        if (path == null) {
            return "";
        }
        if (path.steps() != null) {
            for (var step : path.steps()) {
                if (step != null) {
                    addIfNotBlank(polylines, step.polyline());
                }
            }
        }
        if (path.roads() != null) {
            for (var road : path.roads()) {
                if (road != null && road.steps() != null) {
                    for (var step : road.steps()) {
                        if (step != null) {
                            addIfNotBlank(polylines, step.polyline());
                        }
                    }
                }
            }
        }
        StringJoiner joiner = new StringJoiner(";");
        for (String p : polylines) {
            joiner.add(p);
        }
        return joiner.toString();
    }

    private static void addIfNotBlank(List<String> target, String value) {
        if (value != null && !value.trim().isEmpty()) {
            target.add(value.trim());
        }
    }

    private static String calcKm(AmapPath path) {
        String distanceMetersStr = path.distance();
        if (distanceMetersStr == null || distanceMetersStr.isBlank()) {
            throw new AmapErrorException("高德 API distance 为空");
        }
        BigDecimal meters;
        try {
            meters = new BigDecimal(distanceMetersStr.trim());
        } catch (NumberFormatException e) {
            throw new AmapErrorException("高德 API distance 非数字: " + distanceMetersStr, e);
        }
        return meters.divide(new BigDecimal("1000"), 3, RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatLngLat(double longitude, double latitude) {
        return String.format("%.6f,%.6f", longitude, latitude);
    }
}
