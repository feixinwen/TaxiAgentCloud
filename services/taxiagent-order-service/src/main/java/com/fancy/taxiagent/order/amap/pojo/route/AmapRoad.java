package com.fancy.taxiagent.order.amap.pojo.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * roadaggregation=true 时 paths 下的道路聚合信息（本服务规划请求 roadaggregation=false，仅保留结构兼容）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AmapRoad(List<AmapStep> steps) {
}
