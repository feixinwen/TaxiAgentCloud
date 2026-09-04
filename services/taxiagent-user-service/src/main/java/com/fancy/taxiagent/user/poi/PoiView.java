package com.fancy.taxiagent.user.poi;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 常用地点视图。
 *
 * @param id          主键，创建时由数据库自增生成
 * @param poiTag      标签：家/公司/其他
 * @param poiName     地点名称
 * @param poiAddress  详细地址，可空
 * @param longitude   经度
 * @param latitude    纬度
 * @param createdAt   创建时间
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
