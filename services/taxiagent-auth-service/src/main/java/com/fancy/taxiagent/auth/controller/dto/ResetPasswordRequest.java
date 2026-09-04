package com.fancy.taxiagent.auth.controller.dto;

/**
 * 忘记密码重置请求体。
 *
 * @param email       邮箱
 * @param code        邮箱验证码（RESET_PASSWORD 场景）
 * @param newPassword 新密码（明文）
 */
public record ResetPasswordRequest(String email, String code, String newPassword) {}
