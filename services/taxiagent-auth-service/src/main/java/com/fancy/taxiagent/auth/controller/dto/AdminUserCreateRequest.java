package com.fancy.taxiagent.auth.controller.dto;

/**
 * 管理员创建用户请求。
 *
 * @param username 用户名，必填，≤50 字符且不含 @
 * @param password 初始密码，必填
 * @param role     业务角色，必填，∈ USER|DRIVER|SUPPORT|ADMIN
 */
public record AdminUserCreateRequest(
        String username,
        String password,
        String role
) {
}
