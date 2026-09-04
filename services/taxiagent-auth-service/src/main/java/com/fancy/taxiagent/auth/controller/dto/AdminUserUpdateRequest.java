package com.fancy.taxiagent.auth.controller.dto;

/**
 * 管理员更新用户请求：至少提供一个字段，未提供的字段保持不变。
 *
 * @param userId   目标用户 ID，必填
 * @param username 新用户名（User 侧修改），可为 null
 * @param email    新邮箱（Auth 本地修改），可为 null
 * @param password 新密码（Auth 本地修改并撤销会话），可为 null
 * @param role     新角色（User 侧修改，撤销走事件 E-B），可为 null
 */
public record AdminUserUpdateRequest(
        Long userId,
        String username,
        String email,
        String password,
        String role
) {
}
