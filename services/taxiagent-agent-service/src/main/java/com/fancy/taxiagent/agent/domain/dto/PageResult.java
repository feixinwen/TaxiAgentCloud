package com.fancy.taxiagent.agent.domain.dto;

import java.util.List;

/**
 * 手写分页结果（与 order/ticket 等 cloud 服务同构：page/size/total/records）。
 */
public record PageResult<T>(Integer page, Integer size, Long total, List<T> records) {
}
