package com.fancy.taxiagent.auth.service.dto;

import java.time.LocalDateTime;

/**
 * 当前用户信息视图：Auth 本地凭证数据（email/lastLoginAt/createdAt）
 * 与 User Service 业务资料（username/role/status）的聚合快照。
 *
 * @param userId      全局用户 ID
 * @param username    用户名（User Service）
 * @param email       邮箱（Auth 本地）
 * @param role        业务角色（User Service）
 * @param status      用户业务状态（User Service）
 * @param lastLoginAt 最后登录时间（Auth 本地）
 * @param createdAt   账号创建时间（Auth 本地）
 */
public record CurrentUserView(
        String userId,
        String username,
        String email,
        String role,
        Integer status,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt
) {
}
