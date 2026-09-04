package com.fancy.taxiagent.auth.controller.dto;

/**
 * 登录/注册/刷新成功后返回的统一会话响应体。
 *
 * @param accessToken        已签发的 Bearer Access Token（敏感凭证，禁止写入日志）
 * @param refreshToken       Refresh Token（敏感凭证，禁止写入日志）
 * @param tokenType          固定为 Bearer
 * @param expiresInSec       Access Token 剩余有效秒数
 * @param refreshExpiresInSec Refresh Token 剩余有效秒数
 * @param userId             用户 ID（超出 JS 安全整数范围，接口层序列化为字符串）
 * @param username           实际生效的业务用户名
 * @param role               实际生效的业务角色
 */
public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSec,
        long refreshExpiresInSec,
        String userId,
        String username,
        String role
) {}
