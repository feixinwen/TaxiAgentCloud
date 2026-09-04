package com.fancy.taxiagent.order.amap.pojo.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fancy.taxiagent.order.amap.util.jackson.StringOrEmptyArrayToNullDeserializer;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapRoute(
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String origin,
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class) String destination,
        @JsonProperty("taxi_cost")
        @JsonDeserialize(using = StringOrEmptyArrayToNullDeserializer.class)
        String taxiCost,
        List<AmapPath> paths
) {
}
