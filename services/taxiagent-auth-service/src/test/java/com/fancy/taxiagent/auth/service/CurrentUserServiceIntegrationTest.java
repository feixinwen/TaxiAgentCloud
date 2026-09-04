package com.fancy.taxiagent.auth.service;

import com.fancy.taxiagent.auth.client.user.UserProfileClientResponse;
import com.fancy.taxiagent.auth.client.user.UserProfileRemoteService;
import com.fancy.taxiagent.auth.domain.entity.AuthAccount;
import com.fancy.taxiagent.auth.domain.enums.CredentialStatus;
import com.fancy.taxiagent.auth.exception.AuthApiException;
import com.fancy.taxiagent.auth.mapper.AuthAccountMapper;
import com.fancy.taxiagent.auth.service.dto.CurrentUserView;
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

/**
 * 当前用户三件套（查询/修改/改密）的集成测试：TC MySQL + Redis，
 * 远程 User Service 通过 mock 模拟，上下文装配与 {@link LoginServicePasswordIntegrationTest} 一致。
 */
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
class CurrentUserServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("taxiagent_auth");

    @Container
    @ServiceConnection
    static final RedisContainer REDIS = new RedisContainer("redis:7-alpine");

    @Autowired
    private CurrentUserService currentUserService;

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
    void shouldReturnAggregatedCurrentUser() {
        seedAccount(80001L, "me@example.com", "secret");
        stubProfile(80001L, "me", "USER", 1);

        CurrentUserView view = currentUserService.getCurrent(80001L);

        assertThat(view.username()).isEqualTo("me");
        assertThat(view.email()).isEqualTo("me@example.com");
        assertThat(view.role()).isEqualTo("USER");
        assertThat(view.status()).isEqualTo(1);
    }

    @Test
    void shouldUpdateUsernameAndEmail() {
        seedAccount(80002L, "old@example.com", "secret");
        stubProfile(80002L, "oldname", "USER", 1);
        // 模拟远程 User Service 应用改名：updateUsername 生效后 getProfile 返回新用户名，
        // 这样编排末尾的 getCurrent 重读能看到更新后的资料（与生产行为一致）。
        when(userProfileRemoteService.updateUsername(80002L, "newname")).thenAnswer(invocation -> {
            UserProfileClientResponse updated = profile(80002L, "newname", "USER", 1);
            when(userProfileRemoteService.getProfile(80002L)).thenReturn(updated);
            return updated;
        });

        CurrentUserView view = currentUserService.updateCurrent(80002L, "newname", "new@example.com");

        assertThat(view.username()).isEqualTo("newname");
        assertThat(view.email()).isEqualTo("new@example.com");
        verify(userProfileRemoteService).updateUsername(80002L, "newname");
        assertThat(authAccountMapper.selectById(80002L).getEmail()).isEqualTo("new@example.com");
    }

    @Test
    void shouldRejectEmailTakenByOther() {
        seedAccount(80003L, "a@example.com", "secret");
        seedAccount(80004L, "b@example.com", "secret");
        stubProfile(80003L, "a", "USER", 1);

        AuthApiException conflict = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> currentUserService.updateCurrent(80003L, null, "b@example.com"), AuthApiException.class);

        assertThat(conflict.getCode()).isEqualTo("EMAIL_ALREADY_REGISTERED");
    }

    @Test
    void shouldChangePasswordAndRevokeSessions() {
        seedAccount(80005L, "pw@example.com", "oldpass");
        stubProfile(80005L, "pw", "USER", 1);
        LoginResult session = loginService.loginByPassword("pw@example.com", "oldpass");

        currentUserService.changePassword(80005L, "newpass");

        AuthAccount account = authAccountMapper.selectById(80005L);
        assertThat(new BCryptPasswordEncoder().matches("newpass", account.getPasswordHash())).isTrue();
        assertThat(redisTemplate.hasKey("auth:refresh:" + session.refreshToken())).isFalse();
        assertThat(redisTemplate.opsForValue().get("auth:token_version:80005")).isEqualTo("1");

        // 当前密码已是 newpass，再传 newpass 应被拒绝（SAME_PASSWORD）
        AuthApiException same = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> currentUserService.changePassword(80005L, "newpass"), AuthApiException.class);
        assertThat(same.getCode()).isEqualTo("SAME_PASSWORD");
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
