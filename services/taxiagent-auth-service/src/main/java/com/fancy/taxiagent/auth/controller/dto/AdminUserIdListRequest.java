package com.fancy.taxiagent.auth.controller.dto;

import java.util.List;

/**
 * 管理员批量状态操作请求。
 *
 * @param userIds 目标用户 ID 列表，非空
 * @param action  DISABLE / ACTIVATE / DELETE
 */
public record AdminUserIdListRequest(
        List<Long> userIds,
        String action
) {
}
