package com.fancy.taxiagent.auth.controller.dto;

/**
 * 刷新会话请求体。
 *
 * @param refreshToken Refresh Token
 */
public record RefreshTokenRequest(String refreshToken) {}
