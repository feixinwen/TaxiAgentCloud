package com.fancy.taxiagent.auth.service;

import com.fancy.taxiagent.auth.domain.entity.AuthAccount;
import com.fancy.taxiagent.auth.domain.enums.CredentialStatus;
import com.fancy.taxiagent.auth.mapper.AuthAccountMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

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
class AuthAccountServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("taxiagent_auth");

    @Autowired
    private AuthAccountService authAccountService;

    @Autowired
    private AuthAccountMapper authAccountMapper;

    @Test
    void shouldReportActiveEmailExistence() {
        assertThat(authAccountService.existsActiveByEmail("alice@example.com")).isFalse();

        authAccountService.insert(account(20010L, "alice@example.com"));
        assertThat(authAccountService.existsActiveByEmail("alice@example.com")).isTrue();

        // 逻辑删除须走 deleteById：MyBatis-Plus 的 updateById 不会更新 @TableLogic 字段
        authAccountMapper.deleteById(20010L);
        assertThat(authAccountService.existsActiveByEmail("alice@example.com")).isFalse();
    }

    @Test
    void shouldFindActiveAccountByEmailAndUserId() {
        authAccountService.insert(account(20020L, "login@example.com"));

        AuthAccount byEmail = authAccountService.findActiveByEmail("login@example.com");
        assertThat(byEmail).isNotNull();
        assertThat(byEmail.getUserId()).isEqualTo(20020L);

        AuthAccount byId = authAccountService.findActiveByUserId(20020L);
        assertThat(byId).isNotNull();
        assertThat(byId.getEmail()).isEqualTo("login@example.com");

        assertThat(authAccountService.findActiveByEmail("missing@example.com")).isNull();
        assertThat(authAccountService.findActiveByUserId(99999L)).isNull();
    }

    @Test
    void shouldExcludeLogicallyDeletedAccountFromLookup() {
        authAccountService.insert(account(20021L, "gone@example.com"));
        authAccountMapper.deleteById(20021L);

        assertThat(authAccountService.findActiveByEmail("gone@example.com")).isNull();
        assertThat(authAccountService.findActiveByUserId(20021L)).isNull();
    }

    @Test
    void shouldPersistCredentialStateUpdate() {
        authAccountService.insert(account(20022L, "state@example.com"));
        AuthAccount account = authAccountService.findActiveByEmail("state@example.com");
        account.setFailedLoginCount(3);
        account.setLockedUntil(LocalDateTime.now().plusMinutes(15));
        account.setLastLoginAt(LocalDateTime.now());
        account.setTokenVersion(2L);

        authAccountService.updateCredentialState(account);

        AuthAccount reloaded = authAccountMapper.selectById(20022L);
        assertThat(reloaded.getFailedLoginCount()).isEqualTo(3);
        assertThat(reloaded.getLockedUntil()).isNotNull();
        assertThat(reloaded.getLastLoginAt()).isNotNull();
        assertThat(reloaded.getTokenVersion()).isEqualTo(2L);
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
