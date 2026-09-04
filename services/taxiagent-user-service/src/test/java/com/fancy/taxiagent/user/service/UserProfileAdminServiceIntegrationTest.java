package com.fancy.taxiagent.user.service;

import com.fancy.taxiagent.user.domain.entity.UserProfile;
import com.fancy.taxiagent.user.domain.enums.BatchAction;
import com.fancy.taxiagent.user.domain.enums.UserRole;
import com.fancy.taxiagent.user.dto.UserProfilePageView;
import com.fancy.taxiagent.user.dto.UserProfileView;
import com.fancy.taxiagent.user.exception.UserProfileConflictException;
import com.fancy.taxiagent.user.mapper.UserProfileMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 User Service 管理能力（分页过滤、批量状态、用户名/角色更新）的集成测试。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "taxiagent.rocketmq.enabled=false",
                "taxiagent.rocketmq.publish-interval-ms=3600000"
        }
)
class UserProfileAdminServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("taxiagent_user");

    @Autowired
    private UserProfileService userProfileService;

    @Autowired
    private UserProfileMapper userProfileMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldPageWithFilters() {
        insert(90001L, "alice", UserRole.USER, 1);
        insert(90002L, "alice2", UserRole.USER, 0);
        insert(90003L, "bob", UserRole.SUPPORT, 1);
        // 逻辑删除行只影响 deleted=1 过滤，不影响其余断言
        insert(90004L, "deleted-user", UserRole.USER, 1, 1);

        UserProfilePageView page = userProfileService.pageUsers("ali", null, null, 1, 10);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.records()).extracting(UserProfileView::userId).containsExactlyInAnyOrder(90001L, 90002L);

        UserProfilePageView support = userProfileService.pageUsers(null, UserRole.SUPPORT, null, 1, 10);
        assertThat(support.total()).isEqualTo(1);
        assertThat(support.records().get(0).userId()).isEqualTo(90003L);

        UserProfilePageView deletedOnly = userProfileService.pageUsers(null, null, 1, 1, 10);
        assertThat(deletedOnly.total()).isEqualTo(1);
        assertThat(deletedOnly.records().get(0).userId()).isEqualTo(90004L);
    }

    @Test
    void shouldUpdateUsernameIdempotently() {
        insert(90010L, "carol", UserRole.USER, 1);

        UserProfileView updated = userProfileService.updateUsername(90010L, "carol-new");
        assertThat(updated.username()).isEqualTo("carol-new");

        // idempotent same value
        UserProfileView again = userProfileService.updateUsername(90010L, "carol-new");
        assertThat(again.username()).isEqualTo("carol-new");

        // conflict with another active user of same role
        insert(90011L, "dave", UserRole.USER, 1);
        assertThatThrownBy(() -> userProfileService.updateUsername(90011L, "carol-new"))
                .isInstanceOf(UserProfileConflictException.class);
    }

    @Test
    void shouldBatchStatusChangeAndLogicallyDelete() {
        insert(90020L, "erin", UserRole.USER, 1);
        insert(90021L, "frank", UserRole.USER, 1);

        assertThat(userProfileService.batchStatus(List.of(90020L, 90021L), BatchAction.DISABLE)).isEqualTo(2);
        assertThat(userProfileMapper.selectById(90020L).getStatus()).isZero();
        assertThat(userProfileMapper.selectById(90021L).getStatus()).isZero();

        assertThat(userProfileService.batchStatus(List.of(90020L, 90021L), BatchAction.ACTIVATE)).isEqualTo(2);
        assertThat(userProfileMapper.selectById(90020L).getStatus()).isEqualTo(1);

        assertThat(userProfileService.batchStatus(List.of(90020L, 90021L), BatchAction.DELETE)).isEqualTo(2);
        // selectById 自动附加 is_deleted = 0，逻辑删除后返回 null，因此用原生 SQL 校验删除标记
        Long deletedFlag = jdbcTemplate.queryForObject(
                "SELECT is_deleted FROM user_profile WHERE id = ?",
                Long.class,
                90020L
        );
        assertThat(deletedFlag).isEqualTo(1L);
        Long deletedFlag2 = jdbcTemplate.queryForObject(
                "SELECT is_deleted FROM user_profile WHERE id = ?",
                Long.class,
                90021L
        );
        assertThat(deletedFlag2).isEqualTo(1L);
    }

    private void insert(Long id, String username, UserRole role, int status) {
        insert(id, username, role, status, 0);
    }

    private void insert(Long id, String username, UserRole role, int status, int deleted) {
        UserProfile profile = new UserProfile();
        profile.setId(id);
        profile.setUsername(username);
        profile.setRole(role);
        profile.setStatus(status);
        profile.setDeleted(deleted);
        userProfileMapper.insert(profile);
    }
}
