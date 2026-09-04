package com.fancy.taxiagent.auth.controller.dto;

/**
 * 验证码登录请求体。
 *
 * @param email 邮箱
 * @param code  邮箱验证码
 */
public record EmailCodeLoginRequest(String email, String code) {}
