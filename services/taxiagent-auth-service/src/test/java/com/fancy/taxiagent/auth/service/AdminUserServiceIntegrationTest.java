package com.fancy.taxiagent.auth.service;

import com.fancy.taxiagent.auth.client.user.UserProfileClientResponse;
import com.fancy.taxiagent.auth.client.user.UserProfilePageClientResponse;
import com.fancy.taxiagent.auth.client.user.UserProfileRemoteService;
import com.fancy.taxiagent.auth.domain.entity.AuthAccount;
import com.fancy.taxiagent.auth.domain.enums.CredentialStatus;
import com.fancy.taxiagent.auth.mapper.AuthAccountMapper;
import com.fancy.taxiagent.auth.service.dto.AdminUserPageView;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理员用户管理编排的集成测试：TC MySQL + Redis，远程 User Service 通过 mock 模拟，
 * 上下文装配与 {@link CurrentUserServiceIntegrationTest} 一致。
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
class AdminUserServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("taxiagent_auth");

    @Container
    @ServiceConnection
    static final RedisContainer REDIS = new RedisContainer("redis:7-alpine");

    @Autowired
    private AdminUserService adminUserService;

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
    void shouldCreateAccountWithoutEmail() {
        when(userProfileRemoteService.createProfile(anyLong(), eq("newuser"), eq("SUPPORT")))
                .thenAnswer(inv -> new UserProfileClientResponse(inv.getArgument(0), "newuser", "SUPPORT", 1,
                        LocalDateTime.now(), LocalDateTime.now()));

        Long userId = adminUserService.create("newuser", "secret", "SUPPORT");

        AuthAccount account = authAccountMapper.selectById(userId);
        assertThat(account.getEmail()).isNull();
        assertThat(new BCryptPasswordEncoder().matches("secret", account.getPasswordHash())).isTrue();
    }

    @Test
    void shouldUpdatePasswordAndRevoke() {
        seedAccount(81010L, "admin-pw@example.com", "oldpass");
        stubProfile(81010L, "adminpw", "USER", 1);
        LoginResult session = loginService.loginByPassword("admin-pw@example.com", "oldpass");

        adminUserService.update(81010L, null, null, "newpass", null);

        AuthAccount account = authAccountMapper.selectById(81010L);
        assertThat(new BCryptPasswordEncoder().matches("newpass", account.getPasswordHash())).isTrue();
        assertThat(redisTemplate.hasKey("auth:refresh:" + session.refreshToken())).isFalse();
        assertThat(redisTemplate.opsForValue().get("auth:token_version:81010")).isEqualTo("1");
    }

    @Test
    void shouldUpdateUsernameAndRole() {
        seedAccount(81011L, "admin-name@example.com", "secret");
        stubProfile(81011L, "oldname", "USER", 1);

        adminUserService.update(81011L, "newname", null, null, "SUPPORT");

        verify(userProfileRemoteService).updateUsername(81011L, "newname");
        verify(userProfileRemoteService).updateRole(81011L, "SUPPORT");
    }

    @Test
    void shouldPageWithEmailEnrichment() {
        when(userProfileRemoteService.pageUsers("al", "USER", null, 1, 10))
                .thenReturn(new UserProfilePageClientResponse(2, List.of(
                        new UserProfileClientResponse(81001L, "alice", "USER", 1, LocalDateTime.now(), LocalDateTime.now()),
                        new UserProfileClientResponse(81002L, "alex", "USER", 1, LocalDateTime.now(), LocalDateTime.now()))));
        seedAccount(81001L, "alice@example.com", "secret");
        seedAccount(81002L, "alex@example.com", "secret");

        AdminUserPageView page = adminUserService.page("al", "USER", null, 1, 10);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.records()).extracting(AdminUserPageView.AdminUserRow::email)
                .containsExactlyInAnyOrder("alice@example.com", "alex@example.com");
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
                .thenReturn(new UserProfileClientResponse(userId, username, role, status,
                        LocalDateTime.now(), LocalDateTime.now()));
    }
}
