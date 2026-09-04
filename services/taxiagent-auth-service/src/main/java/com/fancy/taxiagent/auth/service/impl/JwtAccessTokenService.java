package com.fancy.taxiagent.auth.service.impl;

import com.fancy.taxiagent.auth.config.JwtProperties;
import com.fancy.taxiagent.auth.service.AccessTokenService;
import com.fancy.taxiagent.auth.service.dto.AccessTokenSubject;
import com.fancy.taxiagent.auth.service.dto.IssuedAccessToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 使用 Auth RSA 私钥签发 RS256 Access Token 的默认实现。
 */
@Lazy
@Service
public class JwtAccessTokenService implements AccessTokenService {

    private static final Logger log = LoggerFactory.getLogger(JwtAccessTokenService.class);

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    public JwtAccessTokenService(JwtEncoder jwtEncoder, JwtProperties jwtProperties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
    }

    /**
     * 校验最小身份快照并签发只包含必要声明的短期访问令牌。
     */
    @Override
    public IssuedAccessToken issue(AccessTokenSubject subject) {
        ValidatedSubject validated = validate(subject);
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(jwtProperties.getAccessTokenTtl());

        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(requireText(jwtProperties.getKeyId(), "JWT keyId"))
                .build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(requireText(jwtProperties.getIssuer(), "JWT issuer"))
                .subject(validated.userId().toString())
                .audience(List.of(requireText(jwtProperties.getAudience(), "JWT audience")))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("role", validated.role())
                .claim("token_version", validated.tokenVersion())
                .claim("token_type", "access")
                .build();

        String tokenValue = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        log.info(
                "event=access_token_issued userId={} role={} tokenVersion={} expiresAt={}",
                validated.userId(),
                validated.role(),
                validated.tokenVersion(),
                expiresAt
        );
        return new IssuedAccessToken(tokenValue, "Bearer", issuedAt, expiresAt);
    }

    private ValidatedSubject validate(AccessTokenSubject subject) {
        if (subject == null) {
            throw new IllegalArgumentException("Access Token 身份快照不能为空");
        }
        if (subject.userId() == null || subject.userId() <= 0) {
            throw new IllegalArgumentException("Access Token userId 必须为正数");
        }
        String role = requireText(subject.role(), "Access Token role").toUpperCase(Locale.ROOT);
        if (subject.tokenVersion() == null || subject.tokenVersion() < 0) {
            throw new IllegalArgumentException("Access Token tokenVersion 必须为非负数");
        }
        Duration ttl = jwtProperties.getAccessTokenTtl();
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("JWT Access Token 有效期必须大于零");
        }
        return new ValidatedSubject(subject.userId(), role, subject.tokenVersion());
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value.trim();
    }

    private record ValidatedSubject(Long userId, String role, Long tokenVersion) {
    }
}
