package com.fancy.taxiagent.agent.id;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 Agent Service 雪花 ID 的并发唯一性、节点边界和时钟回拨保护。
 */
class SnowflakeIdGeneratorTest {

    @Test
    void shouldGenerateUniqueIdsConcurrently() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(4, 0);
        Set<Long> generated = ConcurrentHashMap.newKeySet();

        IntStream.range(0, 10_000)
                .parallel()
                .forEach(ignored -> generated.add(generator.nextId()));

        assertThat(generated).hasSize(10_000).allMatch(id -> id > 0);
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
                4,
                0,
                () -> invocation.getAndIncrement() == 0 ? firstMillis : firstMillis - 1
        );
        generator.nextId();

        assertThatThrownBy(generator::nextId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("时钟回拨");
    }
}
