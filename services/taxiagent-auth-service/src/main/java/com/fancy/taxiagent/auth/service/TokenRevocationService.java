package com.fancy.taxiagent.auth.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的 Token 撤销与版本管理。
 *
 * <p>两个职责：</p>
 * <ol>
 *   <li>jti 黑名单：{@code auth:token_blacklist:{jti}}，登出后 Access Token 在剩余 TTL 内立即失效；</li>
 *   <li>token_version 计数器：{@code auth:token_version:{userId}}，密码重置/强制下线时自增，使旧 Token 全部失效。</li>
 * </ol>
 */
@Service
public class TokenRevocationService {

    private static final String BLACKLIST_PREFIX = "auth:token_blacklist:";
    private static final String TOKEN_VERSION_PREFIX = "auth:token_version:";

    private final StringRedisTemplate redisTemplate;

    public TokenRevocationService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void blacklistJti(String jti, long ttlSeconds) {
        redisTemplate.opsForValue().set(BLACKLIST_PREFIX + jti, "1", ttlSeconds, TimeUnit.SECONDS);
    }

    public void incrementTokenVersion(Long userId) {
        redisTemplate.opsForValue().increment(TOKEN_VERSION_PREFIX + userId);
    }

    /**
     * 将 Redis 的 token_version 同步为指定值（与 DB 权威值保持一致）。
     */
    public void setTokenVersion(Long userId, long version) {
        redisTemplate.opsForValue().set(TOKEN_VERSION_PREFIX + userId, String.valueOf(version));
    }

    public Long currentTokenVersion(Long userId) {
        String value = redisTemplate.opsForValue().get(TOKEN_VERSION_PREFIX + userId);
        return value == null ? null : Long.parseLong(value);
    }
}
