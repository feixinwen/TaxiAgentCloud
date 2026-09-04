package com.fancy.taxiagent.user.outbox;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.taxiagent.user.domain.entity.UserProfile;
import com.fancy.taxiagent.user.domain.enums.BatchAction;
import com.fancy.taxiagent.user.domain.enums.UserRole;
import com.fancy.taxiagent.user.mapper.UserProfileMapper;
import com.fancy.taxiagent.user.rocketmq.RocketMqProducer;
import com.fancy.taxiagent.user.service.UserProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 验证领域事件 outbox 的集成测试：状态变更与 outbox 写入同事务、
 * 发布成功后标记已发布、发送失败保持待发布。
 *
 * <p>每个测试方法在独立事务中运行并回滚，避免 outbox 表在用例间相互污染。</p>
 */
@Testcontainers
@Transactional
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
class UserEventOutboxIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("taxiagent_user");

    @Autowired
    private UserProfileService userProfileService;

    @Autowired
    private UserEventPublisher userEventPublisher;

    @Autowired
    private UserEventOutboxMapper outboxMapper;

    @Autowired
    private UserProfileMapper userProfileMapper;

    @MockitoBean
    private RocketMqProducer rocketMqProducer;

    @Test
    void shouldWriteOutboxInSameTransactionAsBatchStatus() {
        insertProfile(90030L, "gina", UserRole.USER, 1);
        insertProfile(90031L, "henry", UserRole.USER, 1);

        userProfileService.batchStatus(List.of(90030L, 90031L), BatchAction.DISABLE);

        List<UserEventOutbox> pending = outboxMapper.selectList(
                new LambdaQueryWrapper<UserEventOutbox>().eq(UserEventOutbox::getStatus, 0));
        assertThat(pending).hasSize(2);
        assertThat(pending).extracting(UserEventOutbox::getEventType)
                .containsExactly(UserEventType.USER_DISABLED.name(), UserEventType.USER_DISABLED.name());
    }

    @Test
    void shouldPublishAndMarkPublished() throws Exception {
        insertProfile(90032L, "iris", UserRole.USER, 1);
        userProfileService.batchStatus(List.of(90032L), BatchAction.DELETE);

        when(rocketMqProducer.send(anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        int published = userEventPublisher.publishPending(100);

        assertThat(published).isEqualTo(1);
        List<UserEventOutbox> pending = outboxMapper.selectList(
                new LambdaQueryWrapper<UserEventOutbox>().eq(UserEventOutbox::getStatus, 0));
        assertThat(pending).isEmpty();
    }

    @Test
    void shouldKeepPendingOnSendFailure() throws Exception {
        insertProfile(90033L, "jack", UserRole.USER, 1);
        userProfileService.batchStatus(List.of(90033L), BatchAction.DISABLE);

        when(rocketMqProducer.send(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("mq down"));

        int published = userEventPublisher.publishPending(100);

        assertThat(published).isZero();
        List<UserEventOutbox> pending = outboxMapper.selectList(
                new LambdaQueryWrapper<UserEventOutbox>().eq(UserEventOutbox::getStatus, 0));
        assertThat(pending).hasSize(1);
    }

    private void insertProfile(Long id, String username, UserRole role, int status) {
        UserProfile profile = new UserProfile();
        profile.setId(id);
        profile.setUsername(username);
        profile.setRole(role);
        profile.setStatus(status);
        profile.setDeleted(0);
        userProfileMapper.insert(profile);
    }
}
