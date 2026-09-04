package com.fancy.taxiagent.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.taxiagent.auth.client.user.UserProfileClientResponse;
import com.fancy.taxiagent.auth.client.user.UserProfileRemoteService;
import com.fancy.taxiagent.auth.domain.entity.AuthAccount;
import com.fancy.taxiagent.auth.domain.enums.CredentialStatus;
import com.fancy.taxiagent.auth.exception.AuthApiException;
import com.fancy.taxiagent.auth.mapper.AuthAccountMapper;
import com.fancy.taxiagent.auth.service.dto.LoginResult;
import com.fancy.taxiagent.auth.service.dto.RegisterCommand;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
class RegistrationServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("taxiagent_auth");

    @Container
    @ServiceConnection
    static final RedisContainer REDIS = new RedisContainer("redis:7-alpine");

    @Autowired
    private RegistrationService registrationService;

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
    void shouldRegisterAndIssueAccessToken() {
        stubProfile();
        seedCode("alice@example.com");

        LoginResult result = registrationService.register(
                new RegisterCommand("alice@example.com", "123456", "secret", "alice", "USER"));

        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshToken()).isNotBlank();
        assertThat(result.tokenType()).isEqualTo("Bearer");
        assertThat(result.expiresInSec()).isEqualTo(900);
        assertThat(result.refreshExpiresInSec()).isEqualTo(604800);
        assertThat(result.username()).isEqualTo("alice");
        assertThat(result.role()).isEqualTo("USER");
        verify(userProfileRemoteService).createProfile(eq(result.userId()), eq("alice"), eq("USER"));
        assertThat(redisTemplate.hasKey("auth:email_code:register:alice@example.com")).isFalse();
        assertThat(redisTemplate.hasKey("auth:refresh:" + result.refreshToken())).isTrue();
        AuthAccount saved = authAccountMapper.selectById(Long.valueOf(result.userId()));
        assertThat(saved.getEmail()).isEqualTo("alice@example.com");
        assertThat(new BCryptPasswordEncoder().matches("secret", saved.getPasswordHash())).isTrue();
    }

    @Test
    void shouldIssueRefreshTokenUsableForSessionRefresh() {
        stubProfile();
        stubGetProfile("grace", "USER");
        seedCode("grace@example.com");

        LoginResult registered = registrationService.register(
                new RegisterCommand("grace@example.com", "123456", "secret", "grace", "USER"));
        LoginResult refreshed = loginService.refresh(registered.refreshToken());

        assertThat(refreshed.accessToken()).isNotBlank();
        assertThat(refreshed.refreshToken()).isNotBlank();
        assertThat(refreshed.refreshToken()).isNotEqualTo(registered.refreshToken());
        assertThat(refreshed.userId()).isEqualTo(registered.userId());
        assertThat(refreshed.username()).isEqualTo("grace");
        assertThat(refreshed.role()).isEqualTo("USER");
        // 旧 refresh token 已轮换失效
        assertThat(redisTemplate.hasKey("auth:refresh:" + registered.refreshToken())).isFalse();
    }

    @Test
    void shouldRejectAlreadyRegisteredEmail() {
        seedCode("bob@example.com");
        authAccountMapper.insert(account(30001L, "bob@example.com"));

        assertThatThrownBy(() -> registrationService.register(
                new RegisterCommand("bob@example.com", "123456", "secret", "bob", "USER")))
                .isInstanceOf(AuthApiException.class)
                .extracting(e -> ((AuthApiException) e).getCode())
                .isEqualTo("EMAIL_ALREADY_REGISTERED");
        verifyNoInteractions(userProfileRemoteService);
    }

    @Test
    void shouldRejectUsernameConflictWithoutLocalResidue() {
        stubProfileThrows(new AuthApiException(HttpStatus.CONFLICT, "USERNAME_CONFLICT", "用户名已存在"));
        seedCode("carol@example.com");

        assertThatThrownBy(() -> registrationService.register(
                new RegisterCommand("carol@example.com", "123456", "secret", "carol", "USER")))
                .isInstanceOf(AuthApiException.class)
                .extracting(e -> ((AuthApiException) e).getCode())
                .isEqualTo("USERNAME_CONFLICT");

        assertThat(authAccountMapper.selectCount(new LambdaQueryWrapper<AuthAccount>()
                .eq(AuthAccount::getEmail, "carol@example.com"))).isZero();
        assertThat(redisTemplate.hasKey("auth:email_code:register:carol@example.com")).isTrue();
    }

    @Test
    void shouldAllowRetryAfterRemoteFailureWithoutDirtyState() {
        stubProfileThrows(new IllegalStateException("remote unavailable"));
        seedCode("dave@example.com");

        assertThatThrownBy(() -> registrationService.register(
                new RegisterCommand("dave@example.com", "123456", "secret", "dave", "USER")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(authAccountMapper.selectCount(new LambdaQueryWrapper<AuthAccount>()
                .eq(AuthAccount::getEmail, "dave@example.com"))).isZero();
        assertThat(redisTemplate.hasKey("auth:email_code:register:dave@example.com")).isTrue();

        stubProfile();
        LoginResult retried = registrationService.register(
                new RegisterCommand("dave@example.com", "123456", "secret", "dave", "USER"));
        assertThat(retried.username()).isEqualTo("dave");
    }

    @Test
    void shouldRejectInvalidCode() {
        seedCode("erin@example.com");

        assertThatThrownBy(() -> registrationService.register(
                new RegisterCommand("erin@example.com", "000000", "secret", "erin", "USER")))
                .isInstanceOf(AuthApiException.class)
                .extracting(e -> ((AuthApiException) e).getCode())
                .isEqualTo("INVALID_EMAIL_CODE");
        verifyNoInteractions(userProfileRemoteService);
    }

    @Test
    void shouldDefaultUsernameToUserId() {
        stubProfile();
        seedCode("frank@example.com");

        LoginResult result = registrationService.register(
                new RegisterCommand("frank@example.com", "123456", "secret", null, "DRIVER"));

        assertThat(result.username()).startsWith("user_");
        assertThat(result.role()).isEqualTo("DRIVER");
    }

    // helpers
    private void seedCode(String email) {
        redisTemplate.opsForValue().set("auth:email_code:register:" + email, "123456", 300, TimeUnit.SECONDS);
    }

    private void stubProfile() {
        doAnswer(inv -> new UserProfileClientResponse(
                inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), 1,
                LocalDateTime.now(), LocalDateTime.now()))
                .when(userProfileRemoteService)
                .createProfile(anyLong(), any(), any());
    }

    private void stubProfileThrows(RuntimeException exception) {
        when(userProfileRemoteService.createProfile(anyLong(), any(), any())).thenThrow(exception);
    }

    private void stubGetProfile(String username, String role) {
        when(userProfileRemoteService.getProfile(anyLong()))
                .thenAnswer(inv -> new UserProfileClientResponse(
                        inv.getArgument(0), username, role, 1,
                        LocalDateTime.now(), LocalDateTime.now()));
    }

    private AuthAccount account(Long userId, String email) {
        AuthAccount account = new AuthAccount();
        account.setUserId(userId);
        account.setEmail(email);
        account.setPasswordHash("{bcrypt}test-hash");
        account.setCredentialStatus(CredentialStatus.ACTIVE);
        account.setTokenVersion(0L);
        account.setFailedLoginCount(0);
        account.setDeleted(0);
        return account;
    }
}
