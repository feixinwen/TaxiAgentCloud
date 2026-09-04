package com.fancy.taxiagent.auth.service;

import com.fancy.taxiagent.auth.client.user.UserProfileClientResponse;
import com.fancy.taxiagent.auth.client.user.UserProfileRemoteService;
import com.fancy.taxiagent.auth.domain.entity.AuthAccount;
import com.fancy.taxiagent.auth.domain.enums.CredentialStatus;
import com.fancy.taxiagent.auth.exception.AuthApiException;
import com.fancy.taxiagent.auth.mapper.AuthAccountMapper;
import com.fancy.taxiagent.auth.service.dto.LoginResult;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "taxiagent.auth.jwt.public-key-location=classpath:keys/auth-jwt-public.pem",
                "taxiagent.auth.jwt.private-key-location=classpath:keys/auth-jwt-private.pem",
                "management.health.mail.enabled=false"
        }
)
class LoginServiceSessionIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("taxiagent_auth");

    @Container
    @ServiceConnection
    static final RedisContainer REDIS = new RedisContainer("redis:7-alpine");

    @Autowired
    private LoginService loginService;

    @Autowired
    private AuthAccountMapper authAccountMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private UserProfileRemoteService userProfileRemoteService;

    @MockitoBean
    private JavaMailSender mailSender;

    @Test
    void shouldLoginByEmailCodeForUserRole() {
        seedAccount(60001L, "code@example.com", "secret");
        stubProfile(60001L, "coder", "USER", 1);
        seedCode("code@example.com", "123456");

        LoginResult result = loginService.loginByEmailCode("code@example.com", "123456");

        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshToken()).isNotBlank();
        assertThat(result.role()).isEqualTo("USER");
        assertThat(redisTemplate.hasKey("auth:email_code:login:code@example.com")).isFalse();
    }

    @Test
    void shouldRejectNonUserRoleForCodeLogin() {
        seedAccount(60002L, "driver@example.com", "secret");
        stubProfile(60002L, "drv", "DRIVER", 1);
        seedCode("driver@example.com", "123456");

        AuthApiException e = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> loginService.loginByEmailCode("driver@example.com", "123456"), AuthApiException.class);

        assertThat(e.getStatus().value()).isEqualTo(403);
    }

    @Test
    void shouldRefreshWithRotation() {
        seedAccount(60003L, "refresh@example.com", "secret");
        stubProfile(60003L, "reffy", "USER", 1);
        LoginResult first = loginService.loginByPassword("refresh@example.com", "secret");

        LoginResult second = loginService.refresh(first.refreshToken());

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(redisTemplate.hasKey("auth:refresh:" + first.refreshToken())).isFalse();
    }

    @Test
    void shouldRejectUnknownRefreshToken() {
        AuthApiException e = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> loginService.refresh("unknown-token"), AuthApiException.class);

        assertThat(e.getCode()).isEqualTo("INVALID_REFRESH_TOKEN");
    }

    @Test
    void shouldLogoutRevokeAllAndBlacklistJti() {
        seedAccount(60004L, "out@example.com", "secret");
        stubProfile(60004L, "outty", "USER", 1);
        LoginResult result = loginService.loginByPassword("out@example.com", "secret");

        loginService.logout(60004L, "jti-abc", 900);

        assertThat(redisTemplate.hasKey("auth:refresh:" + result.refreshToken())).isFalse();
        assertThat(redisTemplate.hasKey("auth:token_blacklist:jti-abc")).isTrue();
    }

    @Test
    void shouldReportUsernameAvailability() {
        when(userProfileRemoteService.resolveProfile("taken", "USER"))
                .thenReturn(profile(60005L, "taken", "USER", 1));
        when(userProfileRemoteService.resolveProfile("free", "USER"))
                .thenThrow(new AuthApiException(HttpStatus.NOT_FOUND, "USER_PROFILE_NOT_FOUND", "用户资料不存在"));

        assertThat(loginService.isUsernameAvailable("taken")).isFalse();
        assertThat(loginService.isUsernameAvailable("free")).isTrue();
    }


    private void seedAccount(Long userId, String email, String rawPassword) {
        AuthAccount account = new AuthAccount();
        account.setUserId(userId);
        account.setEmail(email);
        account.setPasswordHash(new BCryptPasswordEncoder().encode(rawPassword));
        account.setCredentialStatus(CredentialStatus.ACTIVE);
        account.setTokenVersion(0L);
        account.setFailedLoginCount(0);
        account.setDeleted(0);
        authAccountMapper.insert(account);
    }

    private void stubProfile(Long userId, String username, String role, int status) {
        when(userProfileRemoteService.getProfile(userId))
                .thenReturn(profile(userId, username, role, status));
    }

    private UserProfileClientResponse profile(Long userId, String username, String role, int status) {
        return new UserProfileClientResponse(userId, username, role, status,
                LocalDateTime.now(), LocalDateTime.now());
    }

    private void seedCode(String email, String code) {
        redisTemplate.opsForValue().set("auth:email_code:login:" + email, code, 300, TimeUnit.SECONDS);
    }
}
