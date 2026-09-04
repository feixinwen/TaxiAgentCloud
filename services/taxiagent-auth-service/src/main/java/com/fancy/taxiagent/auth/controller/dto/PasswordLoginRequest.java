package com.fancy.taxiagent.auth.controller.dto;

/**
 * 密码登录请求体。
 *
 * @param login    登录标识：邮箱，或 username#role（后台用户）
 * @param password 明文密码
 */
public record PasswordLoginRequest(String login, String password) {}
