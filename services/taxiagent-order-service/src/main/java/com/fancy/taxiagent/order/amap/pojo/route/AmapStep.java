package com.fancy.taxiagent.order.amap.pojo.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fancy.taxiagent.order.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapStep(
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String polyline
) {
}
