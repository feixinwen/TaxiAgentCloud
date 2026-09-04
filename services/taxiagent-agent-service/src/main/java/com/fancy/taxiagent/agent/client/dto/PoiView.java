package com.fancy.taxiagent.agent.client.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户服务兴趣点视图（对齐 user-service PoiView 字段）。
 */
public record PoiView(
        Long id,
        String poiTag,
        String poiName,
        String poiAddress,
        BigDecimal longitude,
        BigDecimal latitude,
        LocalDateTime createdAt
) {
}
