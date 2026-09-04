package com.fancy.taxiagent.auth.service;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "taxiagent.auth.jwt.public-key-location=classpath:keys/auth-jwt-public.pem",
                "taxiagent.auth.jwt.private-key-location=classpath:keys/auth-jwt-private.pem"
        }
)
class TokenRevocationServiceTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("taxiagent_auth");

    @Container
    @ServiceConnection
    static final RedisContainer REDIS = new RedisContainer("redis:7-alpine");

    @Autowired
    private TokenRevocationService tokenRevocationService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void shouldBlacklistJtiWithTtl() {
        tokenRevocationService.blacklistJti("jti-123", 900);

        assertThat(redisTemplate.hasKey("auth:token_blacklist:jti-123")).isTrue();
        Long ttl = redisTemplate.getExpire("auth:token_blacklist:jti-123", TimeUnit.SECONDS);
        assertThat(ttl).isBetween(890L, 900L);
    }

    @Test
    void shouldIncrementAndReadTokenVersion() {
        assertThat(tokenRevocationService.currentTokenVersion(40001L)).isNull();

        tokenRevocationService.incrementTokenVersion(40001L);
        tokenRevocationService.incrementTokenVersion(40001L);

        assertThat(tokenRevocationService.currentTokenVersion(40001L)).isEqualTo(2L);
    }
}
