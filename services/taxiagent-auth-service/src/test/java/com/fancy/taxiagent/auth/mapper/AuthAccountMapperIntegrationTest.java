package com.fancy.taxiagent.auth.mapper;

import com.fancy.taxiagent.auth.domain.entity.AuthAccount;
import com.fancy.taxiagent.auth.domain.enums.CredentialStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 MySQL 容器验证 Auth Flyway 脚本、字段映射和关键数据库约束。
 */
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
class AuthAccountMapperIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("taxiagent_auth");

    @Autowired
    private AuthAccountMapper authAccountMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldApplyMigrationAndPersistAccount() {
        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1",
                Integer.class
        );
        assertThat(migrationCount).isEqualTo(3);

        AuthAccount account = newAccount(20001L, "user@example.com");
        assertThat(authAccountMapper.insert(account)).isEqualTo(1);

        AuthAccount saved = authAccountMapper.selectById(20001L);
        assertThat(saved.getEmail()).isEqualTo("user@example.com");
        assertThat(saved.getPasswordHash()).isEqualTo("{bcrypt}test-hash");
        assertThat(saved.getCredentialStatus()).isEqualTo(CredentialStatus.ACTIVE);
        assertThat(saved.getTokenVersion()).isZero();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void shouldRejectDuplicateActiveEmailWithUniqueConstraint() {
        authAccountMapper.insert(newAccount(20002L, "shared@example.com"));

        assertThatThrownBy(() -> authAccountMapper.insert(newAccount(20003L, "shared@example.com")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void shouldAllowEmailReuseAfterLogicalDelete() {
        authAccountMapper.insert(newAccount(20015L, "reuse@example.com"));
        // 逻辑删除须走 deleteById：MyBatis-Plus 的 updateById 不会更新 @TableLogic 字段
        authAccountMapper.deleteById(20015L);

        authAccountMapper.insert(newAccount(20016L, "reuse@example.com"));
        assertThat(authAccountMapper.selectById(20016L).getEmail()).isEqualTo("reuse@example.com");
    }

    @Test
    void shouldRejectDuplicateUserId() {
        authAccountMapper.insert(newAccount(20004L, "first@example.com"));

        assertThatThrownBy(() -> authAccountMapper.insert(newAccount(20004L, "second@example.com")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void shouldHideLogicallyDeletedAccount() {
        authAccountMapper.insert(newAccount(20005L, "deleted@example.com"));

        assertThat(authAccountMapper.deleteById(20005L)).isEqualTo(1);
        assertThat(authAccountMapper.selectById(20005L)).isNull();

        Integer storedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_account WHERE user_id = ? AND is_deleted = 1",
                Integer.class,
                20005L
        );
        assertThat(storedRows).isEqualTo(1);
    }

    private AuthAccount newAccount(long userId, String email) {
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
