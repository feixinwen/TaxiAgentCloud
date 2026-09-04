package com.fancy.taxiagent.auth.id;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证雪花 ID 的节点位、同毫秒序列、参数边界和时钟回拨保护。
 */
class SnowflakeIdGeneratorTest {

    @Test
    void shouldGenerateMonotonicUniqueIdsWithinSameMillisecond() {
        long now = SnowflakeIdGenerator.EPOCH_MILLIS + 10_000;
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(7, 3, () -> now);

        long first = generator.nextId();
        long second = generator.nextId();

        assertThat(second).isEqualTo(first + 1);
        assertThat((first >> 12) & 31).isEqualTo(7);
        assertThat((first >> 17) & 31).isEqualTo(3);
    }

    @Test
    void shouldRejectInvalidNodeIds() {
        assertThatThrownBy(() -> new SnowflakeIdGenerator(32, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workerId");
        assertThatThrownBy(() -> new SnowflakeIdGenerator(0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("datacenterId");
    }

    @Test
    void shouldRejectClockRollback() {
        long firstMillis = SnowflakeIdGenerator.EPOCH_MILLIS + 10_000;
        AtomicInteger invocation = new AtomicInteger();
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(
                1,
                1,
                () -> invocation.getAndIncrement() == 0 ? firstMillis : firstMillis - 1
        );
        generator.nextId();

        assertThatThrownBy(generator::nextId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("时钟回拨");
    }
}
