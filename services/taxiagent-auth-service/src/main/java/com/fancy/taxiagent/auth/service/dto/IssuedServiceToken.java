package com.fancy.taxiagent.auth.service.dto;

import java.time.Instant;

/**
 * 已签发的短期服务身份 Token。
 *
 * @param tokenValue Token 原文，只允许写入 Authorization 请求头，禁止记录日志
 * @param tokenType  固定为 Bearer
 * @param issuedAt   签发时间
 * @param expiresAt  过期时间
 */
public record IssuedServiceToken(
        String tokenValue,
        String tokenType,
        Instant issuedAt,
        Instant expiresAt
) {
}
