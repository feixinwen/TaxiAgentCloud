package com.fancy.taxiagent.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 用户位置相关配置。
 */
@ConfigurationProperties(prefix = "taxiagent.user.location")
public class UserLocationProperties {

    /**
     * 位置 Redis 有效期（秒），默认 30 分钟。
     */
    private long ttlSeconds = 1800;

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }
}
