package com.fancy.taxiagent.order.domain.vo;

import java.util.List;

public record PageResult<T>(Integer page, Integer size, Long total, List<T> records) {
}
