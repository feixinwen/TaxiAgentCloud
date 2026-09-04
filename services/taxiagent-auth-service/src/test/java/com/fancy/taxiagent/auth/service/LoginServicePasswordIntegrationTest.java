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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
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
class LoginServicePasswordIntegrationTest {

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
    void shouldLoginByEmail() {
        seedAccount(50001L, "alice@example.com", "secret");
        stubProfile(50001L, "alice", "USER", 1);

        LoginResult result = loginService.loginByPassword("alice@example.com", "secret");

        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshToken()).isNotBlank();
        assertThat(result.userId()).isEqualTo(50001L);
        assertThat(result.role()).isEqualTo("USER");
        verify(userProfileRemoteService).getProfile(50001L);
        assertThat(redisTemplate.hasKey("auth:refresh:" + result.refreshToken())).isTrue();
        assertThat(authAccountMapper.selectById(50001L).getFailedLoginCount()).isZero();
    }

    @Test
    void shouldLoginByUsername() {
        seedAccount(50002L, "bob@example.com", "secret");
        when(userProfileRemoteService.resolveProfile("bob", "USER"))
                .thenReturn(profile(50002L, "bob", "USER", 1));

        LoginResult result = loginService.loginByPassword("bob", "secret");

        assertThat(result.userId()).isEqualTo(50002L);
        assertThat(result.role()).isEqualTo("USER");
        verify(userProfileRemoteService).resolveProfile("bob", "USER");
    }

    @Test
    void shouldLoginByUsernameWithRole() {
        seedAccount(50003L, "driver@example.com", "secret");
        when(userProfileRemoteService.resolveProfile("driver1", "DRIVER"))
                .thenReturn(profile(50003L, "driver1", "DRIVER", 1));

        LoginResult result = loginService.loginByPassword("driver1#DRIVER", "secret");

        assertThat(result.role()).isEqualTo("DRIVER");
    }

    @Test
    void shouldReturnSameErrorForUnknownAccountAndWrongPassword() {
        AuthApiException unknown = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> loginService.loginByPassword("nobody@example.com", "x"), AuthApiException.class);
        assertThat(unknown.getCode()).isEqualTo("INVALID_CREDENTIALS");

        seedAccount(50004L, "carol@example.com", "secret");
        stubProfile(50004L, "carol", "USER", 1);
        AuthApiException wrong = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> loginService.loginByPassword("carol@example.com", "wrong"), AuthApiException.class);
        assertThat(wrong.getCode()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void shouldLockAfterFiveFailuresAndUnlockAfterDuration() {
        seedAccount(50005L, "dave@example.com", "secret");
        stubProfile(50005L, "dave", "USER", 1);

        for (int i = 0; i < 4; i++) {
            AuthApiException e = org.assertj.core.api.Assertions.catchThrowableOfType(
                    () -> loginService.loginByPassword("dave@example.com", "wrong"), AuthApiException.class);
            assertThat(e.getCode()).isEqualTo("INVALID_CREDENTIALS");
        }

        AuthApiException locked = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> loginService.loginByPassword("dave@example.com", "wrong"), AuthApiException.class);
        assertThat(locked.getCode()).isEqualTo("ACCOUNT_LOCKED");

        AuthAccount account = authAccountMapper.selectById(50005L);
        assertThat(account.getFailedLoginCount()).isZero();
        assertThat(account.getLockedUntil()).isAfter(LocalDateTime.now());

        AuthApiException stillLocked = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> loginService.loginByPassword("dave@example.com", "secret"), AuthApiException.class);
        assertThat(stillLocked.getCode()).isEqualTo("ACCOUNT_LOCKED");
    }

    @Test
    void shouldRejectDisabledAccount() {
        seedAccount(50006L, "erin@example.com", "secret");
        stubProfile(50006L, "erin", "USER", 0);

        AuthApiException e = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> loginService.loginByPassword("erin@example.com", "secret"), AuthApiException.class);

        assertThat(e.getCode()).isEqualTo("ACCOUNT_DISABLED");
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
}
