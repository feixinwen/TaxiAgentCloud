package com.fancy.taxiagent.user.dto;

import com.fancy.taxiagent.user.domain.enums.BatchAction;

import java.util.List;

/**
 * 批量状态变更请求。
 *
 * @param userIds 目标用户ID列表
 * @param action  批量操作
 */
public record BatchStatusRequest(
        List<Long> userIds,
        BatchAction action
) {
}
