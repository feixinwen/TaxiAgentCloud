package com.fancy.taxiagent.auth.client.user;

import java.util.List;

/**
 * 批量状态操作请求（User Service 内部契约）。
 *
 * @param userIds 目标用户 ID 列表
 * @param action  DISABLE / ACTIVATE / DELETE
 */
public record BatchStatusClientRequest(List<Long> userIds, String action) {
}
