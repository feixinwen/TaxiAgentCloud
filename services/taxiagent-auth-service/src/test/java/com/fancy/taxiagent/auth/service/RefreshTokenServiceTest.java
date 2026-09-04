package com.fancy.taxiagent.auth.service;

import com.fancy.taxiagent.auth.service.dto.AccessTokenSubject;
import com.fancy.taxiagent.auth.service.dto.IssuedAccessToken;
import com.fancy.taxiagent.auth.service.dto.LoginResult;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
class RefreshTokenServiceTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("taxiagent_auth");

    @Container
    @ServiceConnection
    static final RedisContainer REDIS = new RedisContainer("redis:7-alpine");

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private AccessTokenService accessTokenService;

    @Test
    void shouldIssuePairAndStoreRefreshToken() {
        when(accessTokenService.issue(any(AccessTokenSubject.class)))
                .thenReturn(new IssuedAccessToken("jwt-token", "Bearer", Instant.now(), Instant.now().plusSeconds(900)));

        LoginResult result = refreshTokenService.issuePair(10001L, "USER", 0L, "alice");

        assertThat(result.accessToken()).isEqualTo("jwt-token");
        assertThat(result.refreshToken()).isNotBlank();
        assertThat(result.expiresInSec()).isEqualTo(900);
        assertThat(result.refreshExpiresInSec()).isEqualTo(604800);
        assertThat(redisTemplate.opsForValue().get("auth:refresh:" + result.refreshToken())).isEqualTo("10001");
        assertThat(redisTemplate.opsForSet().members("auth:refresh:user:10001")).contains(result.refreshToken());
    }

    @Test
    void shouldRotateAndInvalidateOldToken() {
        when(accessTokenService.issue(any(AccessTokenSubject.class)))
                .thenReturn(new IssuedAccessToken("jwt-token", "Bearer", Instant.now(), Instant.now().plusSeconds(900)));

        LoginResult first = refreshTokenService.issuePair(10002L, "USER", 0L, "bob");
        LoginResult second = refreshTokenService.rotate(first.refreshToken(), "USER", "bob");

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(redisTemplate.hasKey("auth:refresh:" + first.refreshToken())).isFalse();
        assertThat(redisTemplate.hasKey("auth:refresh:" + second.refreshToken())).isTrue();
        assertThat(refreshTokenService.resolveUserId(first.refreshToken())).isNull();
        assertThat(refreshTokenService.resolveUserId(second.refreshToken())).isEqualTo(10002L);
    }

    @Test
    void shouldReturnNullUserIdForUnknownToken() {
        assertThat(refreshTokenService.resolveUserId("unknown-token")).isNull();
    }

    @Test
    void shouldRevokeAllTokensForUser() {
        when(accessTokenService.issue(any(AccessTokenSubject.class)))
                .thenReturn(new IssuedAccessToken("jwt-token", "Bearer", Instant.now(), Instant.now().plusSeconds(900)));

        LoginResult first = refreshTokenService.issuePair(10003L, "USER", 0L, "carol");
        LoginResult second = refreshTokenService.issuePair(10003L, "USER", 0L, "carol");

        refreshTokenService.revokeAll(10003L);

        assertThat(redisTemplate.hasKey("auth:refresh:" + first.refreshToken())).isFalse();
        assertThat(redisTemplate.hasKey("auth:refresh:" + second.refreshToken())).isFalse();
        assertThat(redisTemplate.hasKey("auth:refresh:user:10003")).isFalse();
    }
}
