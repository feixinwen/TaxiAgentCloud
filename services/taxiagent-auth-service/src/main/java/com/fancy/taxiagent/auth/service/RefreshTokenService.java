package com.fancy.taxiagent.auth.service;

import com.fancy.taxiagent.auth.exception.AuthApiException;
import com.fancy.taxiagent.auth.service.dto.AccessTokenSubject;
import com.fancy.taxiagent.auth.service.dto.IssuedAccessToken;
import com.fancy.taxiagent.auth.service.dto.LoginResult;
import com.fancy.taxiagent.auth.config.TokenProperties;
import org.slf4j.Logger;
import org.springframework.context.annotation.Lazy;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@Lazy
public class RefreshTokenService {

    private static final String REFRESH_KEY_PREFIX = "auth:refresh:";
    private static final String USER_INDEX_PREFIX = "auth:refresh:user:";
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final AccessTokenService accessTokenService;
    private final TokenProperties tokenProperties;

    public RefreshTokenService(StringRedisTemplate redisTemplate,
                               AccessTokenService accessTokenService,
                               TokenProperties tokenProperties) {
        this.redisTemplate = redisTemplate;
        this.accessTokenService = accessTokenService;
        this.tokenProperties = tokenProperties;
    }

    public LoginResult issuePair(Long userId, String role, long tokenVersion, String username) {
        IssuedAccessToken issued = accessTokenService.issue(new AccessTokenSubject(userId, role, tokenVersion));
        String refreshToken = generateToken();
        redisTemplate.opsForValue().set(
                refreshKey(refreshToken),
                String.valueOf(userId),
                tokenProperties.getRefreshTtlSeconds(),
                TimeUnit.SECONDS
        );
        redisTemplate.opsForSet().add(userIndexKey(userId), refreshToken);
        log.info("event=auth_refresh_token_issued userId={}", userId);
        return new LoginResult(
                issued.tokenValue(),
                refreshToken,
                issued.tokenType(),
                Duration.between(issued.issuedAt(), issued.expiresAt()).getSeconds(),
                tokenProperties.getRefreshTtlSeconds(),
                userId,
                username,
                role
        );
    }

    public LoginResult rotate(String refreshToken, String role, String username) {
        Long userId = resolveUserId(refreshToken);
        if (userId == null) {
            throw new AuthApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "登录已过期，请重新登录");
        }
        redisTemplate.delete(refreshKey(refreshToken));
        redisTemplate.opsForSet().remove(userIndexKey(userId), refreshToken);
        String versionValue = redisTemplate.opsForValue().get("auth:token_version:" + userId);
        long tokenVersion = versionValue == null ? 0L : Long.parseLong(versionValue);
        return issuePair(userId, role, tokenVersion, username);
    }

    public void revokeAll(Long userId) {
        String indexKey = userIndexKey(userId);
        Set<String> tokens = redisTemplate.opsForSet().members(indexKey);
        if (tokens != null) {
            for (String token : tokens) {
                redisTemplate.delete(refreshKey(token));
            }
        }
        redisTemplate.delete(indexKey);
    }

    public Long resolveUserId(String refreshToken) {
        String value = redisTemplate.opsForValue().get(refreshKey(refreshToken));
        return value == null ? null : Long.valueOf(value);
    }

    public String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String refreshKey(String token) {
        return REFRESH_KEY_PREFIX + token;
    }

    private String userIndexKey(Long userId) {
        return USER_INDEX_PREFIX + userId;
    }
}
