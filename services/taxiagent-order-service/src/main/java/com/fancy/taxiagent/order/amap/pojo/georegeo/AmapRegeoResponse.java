package com.fancy.taxiagent.order.amap.pojo.georegeo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fancy.taxiagent.order.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;

/**
 * 逆地理编码根响应对象。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapRegeoResponse(
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String status,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String info,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String infocode,
        Regeocode regeocode
) {
    public boolean isSuccess() {
        return "1".equals(status);
    }
}
