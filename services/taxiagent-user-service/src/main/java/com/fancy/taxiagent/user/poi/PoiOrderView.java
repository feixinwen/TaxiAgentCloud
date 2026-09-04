package com.fancy.taxiagent.user.poi;

import java.util.List;

/**
 * 供下单流程使用的常用地点分组视图。
 *
 * @param home  标签为"家"的地点，无则 null
 * @param work  标签为"公司"的地点，无则 null
 * @param other 其余标签的地点，无则空列表
 */
public record PoiOrderView(PoiView home, PoiView work, List<PoiView> other) {
}
