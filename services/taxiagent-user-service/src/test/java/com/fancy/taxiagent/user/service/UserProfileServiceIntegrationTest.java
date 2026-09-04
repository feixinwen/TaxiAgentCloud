package com.fancy.taxiagent.user.service;

import com.fancy.taxiagent.user.command.CreateUserProfileCommand;
import com.fancy.taxiagent.user.domain.enums.UserRole;
import com.fancy.taxiagent.user.dto.UserProfileView;
import com.fancy.taxiagent.user.exception.InvalidUserProfileException;
import com.fancy.taxiagent.user.exception.UserProfileConflictException;
import com.fancy.taxiagent.user.exception.UserProfileNotFoundException;
import com.fancy.taxiagent.user.mapper.UserProfileMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 MySQL 容器验证 Flyway、MyBatis-Plus 与用户资料业务规则的集成测试。
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
class UserProfileServiceIntegrationTest {

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
    void shouldApplyMigrationAndCreateProfile() {
        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1",
                Integer.class
        );
        assertThat(migrationCount).isEqualTo(4);

        UserProfileView created = userProfileService.createProfile(
                new CreateUserProfileCommand(10001L, "  first-user  ", UserRole.USER)
        );

        assertThat(created.userId()).isEqualTo(10001L);
        assertThat(created.username()).isEqualTo("first-user");
        assertThat(created.role()).isEqualTo(UserRole.USER);
        assertThat(created.status()).isEqualTo(1);
        assertThat(created.createdAt()).isNotNull();
        assertThat(created.updatedAt()).isNotNull();

        UserProfileView queried = userProfileService.getProfile(10001L);
        assertThat(queried).isEqualTo(created);

        UserProfileView resolved = userProfileService.resolveProfile(" first-user ", UserRole.USER);
        assertThat(resolved).isEqualTo(created);
    }

    @Test
    void shouldTreatSameCreateCommandAsIdempotent() {
        CreateUserProfileCommand command =
                new CreateUserProfileCommand(10002L, "idempotent-user", UserRole.DRIVER);

        UserProfileView first = userProfileService.createProfile(command);
        UserProfileView second = userProfileService.createProfile(command);

        assertThat(second).isEqualTo(first);
        assertThat(userProfileMapper.selectCount(null)).isGreaterThanOrEqualTo(1L);
        Long matchingProfiles = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_profile WHERE id = ?",
                Long.class,
                command.userId()
        );
        assertThat(matchingProfiles).isEqualTo(1L);
    }

    @Test
    void shouldRejectDuplicateUsernameForDifferentUsers() {
        userProfileService.createProfile(
                new CreateUserProfileCommand(10003L, "duplicate-user", UserRole.USER)
        );

        assertThatThrownBy(() -> userProfileService.createProfile(
                new CreateUserProfileCommand(10004L, "duplicate-user", UserRole.USER)
        ))
                .isInstanceOf(UserProfileConflictException.class)
                .hasMessage("用户名已存在");
    }

    @Test
    void shouldAllowSameUsernameForDifferentRoles() {
        UserProfileView user = userProfileService.createProfile(
                new CreateUserProfileCommand(10005L, "shared-name", UserRole.USER)
        );
        UserProfileView driver = userProfileService.createProfile(
                new CreateUserProfileCommand(10006L, "shared-name", UserRole.DRIVER)
        );

        assertThat(user.username()).isEqualTo(driver.username());
        assertThat(user.role()).isEqualTo(UserRole.USER);
        assertThat(driver.role()).isEqualTo(UserRole.DRIVER);
    }

    @Test
    void shouldAllowUsernameReuseAfterLogicalDeletion() {
        userProfileService.createProfile(
                new CreateUserProfileCommand(10007L, "reusable-name", UserRole.USER)
        );

        assertThat(userProfileMapper.deleteById(10007L)).isEqualTo(1);

        UserProfileView recreated = userProfileService.createProfile(
                new CreateUserProfileCommand(10008L, "reusable-name", UserRole.USER)
        );
        assertThat(recreated.userId()).isEqualTo(10008L);
    }

    @Test
    void shouldRejectBlankUsername() {
        assertThatThrownBy(() -> userProfileService.createProfile(
                new CreateUserProfileCommand(10009L, "   ", UserRole.USER)
        ))
                .isInstanceOf(InvalidUserProfileException.class)
                .hasMessage("用户名不能为空");
    }

    @Test
    void shouldReportMissingProfile() {
        assertThatThrownBy(() -> userProfileService.getProfile(99999L))
                .isInstanceOf(UserProfileNotFoundException.class)
                .hasMessageContaining("99999");
    }
}
