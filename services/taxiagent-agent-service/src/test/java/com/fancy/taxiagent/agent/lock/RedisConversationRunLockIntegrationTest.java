package com.fancy.taxiagent.agent.lock;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用真实 Redis 验证对话运行锁的原子获取、过期时间和所有者释放语义。
 */
@Testcontainers
@SpringBootTest(
        classes = RedisConversationRunLockIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class RedisConversationRunLockIntegrationTest {

    private static final String CONVERSATION_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String LOCK_KEY = "agent:conversation:{" + CONVERSATION_ID + "}:run-lock";

    @Container
    @ServiceConnection
    static final RedisContainer REDIS = new RedisContainer("redis:7-alpine");

    @Autowired
    private ConversationRunLock conversationRunLock;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanRedis() {
        Set<String> keys = redisTemplate.keys("agent:conversation:*:run-lock");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void shouldAcquireOnceAndApplyRequestedTtl() {
        boolean first = conversationRunLock.tryAcquire(
                CONVERSATION_ID,
                "run-1",
                Duration.ofSeconds(90)
        );
        boolean second = conversationRunLock.tryAcquire(
                CONVERSATION_ID,
                "run-2",
                Duration.ofSeconds(90)
        );

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(redisTemplate.opsForValue().get(LOCK_KEY)).isEqualTo("run-1");
        assertThat(redisTemplate.getExpire(LOCK_KEY)).isBetween(85L, 90L);
    }

    @Test
    void shouldOnlyReleaseWhenRunIdMatches() {
        conversationRunLock.tryAcquire(CONVERSATION_ID, "run-owner", Duration.ofSeconds(90));

        conversationRunLock.release(CONVERSATION_ID, "run-other");
        assertThat(conversationRunLock.tryAcquire(CONVERSATION_ID, "run-next", Duration.ofSeconds(90)))
                .isFalse();

        conversationRunLock.release(CONVERSATION_ID, "run-owner");
        assertThat(conversationRunLock.tryAcquire(CONVERSATION_ID, "run-next", Duration.ofSeconds(90)))
                .isTrue();
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(RedisAutoConfiguration.class)
    @Import(RedisConversationRunLock.class)
    static class TestApplication {
    }
}
