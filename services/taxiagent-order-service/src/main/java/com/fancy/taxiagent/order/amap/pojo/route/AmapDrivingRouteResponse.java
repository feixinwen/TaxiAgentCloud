package com.fancy.taxiagent.order.amap.pojo.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fancy.taxiagent.order.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;

/**
 * 高德驾车路径规划 API 响应。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapDrivingRouteResponse(
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String status,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String info,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String infocode,
        AmapRoute route
) {
    public boolean isSuccess() {
        return "1".equals(status);
    }
}
