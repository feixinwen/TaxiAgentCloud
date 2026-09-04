package com.fancy.taxiagent.auth.service.dto;

import java.time.Instant;

/**
 * 已签发的短期访问令牌。
 *
 * <p>{@code tokenValue} 是敏感凭证，只能返回给经过认证的调用方，禁止写入日志。</p>
 *
 * @param tokenValue JWT 字符串
 * @param tokenType 固定为 Bearer
 * @param issuedAt 签发时间
 * @param expiresAt 过期时间
 */
public record IssuedAccessToken(
        String tokenValue,
        String tokenType,
        Instant issuedAt,
        Instant expiresAt
) {
}
