package com.fancy.taxiagent.auth.controller.dto;

/**
 * 公开注册的请求体。
 *
 * @param email    邮箱
 * @param code     邮箱验证码
 * @param password 明文密码
 * @param username 业务用户名，可为 null（注册后使用 user_{userId} 兜底）
 * @param role     业务角色，可为 null（默认 USER），仅支持 USER / DRIVER
 */
public record RegisterRequest(String email, String code, String password, String username, String role) {}
