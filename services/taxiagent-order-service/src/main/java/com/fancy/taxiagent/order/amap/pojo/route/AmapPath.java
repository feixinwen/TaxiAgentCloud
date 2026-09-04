package com.fancy.taxiagent.order.amap.pojo.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fancy.taxiagent.order.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapPath(
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String distance,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String duration,
        List<AmapRoad> roads,
        List<AmapStep> steps
) {
}
