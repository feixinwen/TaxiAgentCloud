package com.fancy.taxiagent.order.amap.pojo.georegeo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fancy.taxiagent.order.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;

/**
 * 逆地理编码主体（本服务仅使用格式化地址）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Regeocode(
        @JsonProperty("formatted_address")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String formattedAddress
) {
}
