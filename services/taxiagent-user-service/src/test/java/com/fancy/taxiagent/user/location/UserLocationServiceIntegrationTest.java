package com.fancy.taxiagent.user.location;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证位置存取、TTL 过期与坐标校验（真实 Redis 容器）。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "taxiagent.rocketmq.enabled=false",
                "taxiagent.user.location.ttl-seconds=1"
        }
)
class UserLocationServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("taxiagent_user");

    @Container
    @ServiceConnection
    static final RedisContainer REDIS = new RedisContainer("redis:7-alpine");

    @Autowired
    private UserLocationService userLocationService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void shouldSaveAndGetLocation() {
        userLocationService.save(90001L, new UserLocationView("31.2304", "121.4737", "上海市黄浦区"));

        UserLocationView location = userLocationService.get(90001L);

        assertThat(location.latitude()).isEqualTo("31.2304");
        assertThat(location.longitude()).isEqualTo("121.4737");
        assertThat(location.address()).isEqualTo("上海市黄浦区");
        assertThat(redisTemplate.getExpire("user:loc:90001", TimeUnit.SECONDS)).isBetween(0L, 1L);
    }

    @Test
    void shouldReturnNotFoundWhenAbsent() {
        assertThatThrownBy(() -> userLocationService.get(99999L))
                .isInstanceOf(LocationNotFoundException.class);
    }

    @Test
    void shouldExpireAfterTtl() throws InterruptedException {
        userLocationService.save(90002L, new UserLocationView("31.0", "121.0", null));

        Thread.sleep(1500);

        assertThatThrownBy(() -> userLocationService.get(90002L))
                .isInstanceOf(LocationNotFoundException.class);
    }

    @Test
    void shouldRejectInvalidCoordinates() {
        assertThatThrownBy(() -> userLocationService.save(90003L, new UserLocationView("91", "121", null)))
                .isInstanceOf(InvalidLocationException.class);
        assertThatThrownBy(() -> userLocationService.save(90003L, new UserLocationView("31", "181", null)))
                .isInstanceOf(InvalidLocationException.class);
        assertThatThrownBy(() -> userLocationService.save(90003L, new UserLocationView("abc", "121", null)))
                .isInstanceOf(InvalidLocationException.class);
        assertThatThrownBy(() -> userLocationService.save(90003L, new UserLocationView(null, "121", null)))
                .isInstanceOf(InvalidLocationException.class);
    }
}
