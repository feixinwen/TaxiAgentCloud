package com.fancy.taxiagent.auth.id;

import java.time.Instant;
import java.util.function.LongSupplier;

/**
 * 采用 41 位时间戳、5 位数据中心、5 位工作节点和 12 位序列号的雪花 ID 生成器。
 *
 * <p>节点号由部署环境显式分配。检测到系统时钟回拨时直接失败，避免静默生成重复 ID；
 * 单毫秒序列耗尽时等待进入下一毫秒。</p>
 */
public class SnowflakeIdGenerator implements IdGenerator {

    static final long EPOCH_MILLIS = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli();
    static final int MAX_NODE_ID = 31;

    private static final int WORKER_BITS = 5;
    private static final int DATACENTER_BITS = 5;
    private static final int SEQUENCE_BITS = 12;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;
    private static final int WORKER_SHIFT = SEQUENCE_BITS;
    private static final int DATACENTER_SHIFT = SEQUENCE_BITS + WORKER_BITS;
    private static final int TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_BITS + DATACENTER_BITS;

    private final int workerId;
    private final int datacenterId;
    private final LongSupplier currentTimeMillis;

    private long lastTimestamp = -1L;
    private long sequence;

    /**
     * 使用系统 UTC 时间创建生成器。
     *
     * @param workerId     当前副本工作节点号，范围 0-31
     * @param datacenterId 数据中心或集群编号，范围 0-31
     */
    public SnowflakeIdGenerator(int workerId, int datacenterId) {
        this(workerId, datacenterId, System::currentTimeMillis);
    }

    SnowflakeIdGenerator(int workerId, int datacenterId, LongSupplier currentTimeMillis) {
        this.workerId = validateNodeId(workerId, "workerId");
        this.datacenterId = validateNodeId(datacenterId, "datacenterId");
        this.currentTimeMillis = currentTimeMillis;
    }

    /**
     * 在线程安全的临界区内生成 ID，并保护序列号及上一时间戳状态。
     */
    @Override
    public synchronized long nextId() {
        long timestamp = currentTimeMillis.getAsLong();
        if (timestamp < EPOCH_MILLIS) {
            throw new IllegalStateException("系统时间早于雪花算法纪元");
        }
        if (timestamp < lastTimestamp) {
            throw new IllegalStateException(
                    "检测到系统时钟回拨，拒绝生成可能重复的 ID: rollbackMillis=" + (lastTimestamp - timestamp)
            );
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = waitUntilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0;
        }

        lastTimestamp = timestamp;
        return ((timestamp - EPOCH_MILLIS) << TIMESTAMP_SHIFT)
                | ((long) datacenterId << DATACENTER_SHIFT)
                | ((long) workerId << WORKER_SHIFT)
                | sequence;
    }

    private long waitUntilNextMillis(long previousTimestamp) {
        long timestamp = currentTimeMillis.getAsLong();
        while (timestamp <= previousTimestamp) {
            Thread.onSpinWait();
            timestamp = currentTimeMillis.getAsLong();
        }
        return timestamp;
    }

    private int validateNodeId(int value, String fieldName) {
        if (value < 0 || value > MAX_NODE_ID) {
            throw new IllegalArgumentException(fieldName + " 必须在0到31之间");
        }
        return value;
    }
}
