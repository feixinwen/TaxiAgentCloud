package com.fancy.taxiagent.auth.service.dto;

/**
 * 公开注册请求的编排入参。
 *
 * @param email    邮箱（大小写不敏感，注册时统一转小写）
 * @param code     邮箱验证码
 * @param password 明文密码，仅在本服务内用于 BCrypt 加密后持久化
 * @param username 业务用户名，可为 null（此时注册后使用 user_{userId} 兜底）
 * @param role     业务角色，可为 null（默认 USER），仅支持 USER / DRIVER
 */
public record RegisterCommand(
        String email,
        String code,
        String password,
        String username,
        String role
) {
}
