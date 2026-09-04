package com.fancy.taxiagent.user.dto;

import com.fancy.taxiagent.user.domain.enums.UserRole;

/**
 * 角色更新请求。
 *
 * @param role 新角色
 */
public record RoleUpdateRequest(
        UserRole role
) {
}
