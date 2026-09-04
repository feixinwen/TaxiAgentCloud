package com.fancy.taxiagent.order.domain.vo;

/**
 * 对外轨迹查询结果（polyline 为高德「lng,lat;lng,lat;…」分号分段格式）。
 *
 * <p>{@code estPolyline} 估价阶段写入；{@code realPolyline} 行程结束后由司机端写入，
 * 未开始/未结束时为 null。</p>
 */
public record OrderRouteVO(
        String traceId,
        String estPolyline,
        String realPolyline
) {
}
