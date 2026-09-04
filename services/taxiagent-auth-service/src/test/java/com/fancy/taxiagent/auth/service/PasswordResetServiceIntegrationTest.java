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
class PasswordResetServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("taxiagent_auth");

    @Container
    @ServiceConnection
    static final RedisContainer REDIS = new RedisContainer("redis:7-alpine");

    @Autowired
    private PasswordResetService passwordResetService;

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
    void shouldResetPasswordAndRevokeSessions() {
        seedAccount(70001L, "reset@example.com", "oldpass");
        stubProfile(70001L, "resety", "USER", 1);
        LoginResult session = loginService.loginByPassword("reset@example.com", "oldpass");
        seedCode("reset@example.com", "654321");

        passwordResetService.reset("reset@example.com", "654321", "newpass");

        AuthAccount account = authAccountMapper.selectById(70001L);
        assertThat(new BCryptPasswordEncoder().matches("newpass", account.getPasswordHash())).isTrue();
        assertThat(account.getTokenVersion()).isEqualTo(1L);
        assertThat(redisTemplate.hasKey("auth:refresh:" + session.refreshToken())).isFalse();
        assertThat(redisTemplate.opsForValue().get("auth:token_version:70001")).isEqualTo("1");
        assertThat(redisTemplate.hasKey("auth:email_code:reset_password:reset@example.com")).isFalse();
    }

    @Test
    void shouldClearLockStateOnResetAndAllowLoginWithNewPassword() {
        seedAccount(70002L, "lock@example.com", "oldpass");
        AuthAccount locked = authAccountMapper.selectById(70002L);
        locked.setFailedLoginCount(5);
        locked.setLockedUntil(LocalDateTime.now().plusMinutes(15));
        authAccountMapper.updateById(locked);
        stubProfile(70002L, "locky", "USER", 1);
        seedCode("lock@example.com", "222333");

        passwordResetService.reset("lock@example.com", "222333", "newpass");

        AuthAccount account = authAccountMapper.selectById(70002L);
        assertThat(account.getLockedUntil()).isNull();
        assertThat(account.getFailedLoginCount()).isZero();

        // 锁定已清除 → 用新密码登录成功
        LoginResult login = loginService.loginByPassword("lock@example.com", "newpass");
        assertThat(login.accessToken()).isNotBlank();
        assertThat(login.refreshToken()).isNotBlank();
    }

    @Test
    void shouldRejectEmptyNewPassword() {
        seedCode("a@b.com", "123456");

        AuthApiException e = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> passwordResetService.reset("a@b.com", "123456", " "), AuthApiException.class);

        assertThat(e.getCode()).isEqualTo("INVALID_PASSWORD");
    }

    @Test
    void shouldRejectUnknownAccount() {
        seedCode("ghost@example.com", "123456");

        AuthApiException e = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> passwordResetService.reset("ghost@example.com", "123456", "newpass"), AuthApiException.class);

        assertThat(e.getCode()).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    // helpers
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
        redisTemplate.opsForValue().set("auth:email_code:reset_password:" + email, code, 300, TimeUnit.SECONDS);
    }
}
