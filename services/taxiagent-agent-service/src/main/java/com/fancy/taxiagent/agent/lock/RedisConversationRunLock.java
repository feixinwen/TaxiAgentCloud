package com.fancy.taxiagent.agent.lock;

import com.fancy.taxiagent.agent.filter.RequestTraceFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 基于 Redis SET NX 和 Lua compare-and-delete 的对话运行锁。
 */
@Component
public class RedisConversationRunLock implements ConversationRunLock {

    private static final String KEY_PREFIX = "agent:conversation:{";
    private static final String KEY_SUFFIX = "}:run-lock";
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] "
                    + "then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );
    private static final Logger log = LoggerFactory.getLogger(RedisConversationRunLock.class);

    private final StringRedisTemplate redisTemplate;

    public RedisConversationRunLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 使用 Redis 原子 SET NX 获取带 TTL 的运行锁。
     */
    @Override
    public boolean tryAcquire(String conversationId, String runId, Duration ttl) {
        requireText(conversationId, "conversationId");
        requireText(runId, "runId");
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl 必须为正数");
        }
        boolean acquired = Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(lockKey(conversationId), runId, ttl)
        );
        log.info(
                "event=agent_conversation_lock_attempt traceId={} conversationId={} runId={} acquired={}",
                MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY),
                conversationId,
                runId,
                acquired
        );
        return acquired;
    }

    /**
     * 使用 Lua 保证读取所有者与删除操作的原子性。
     */
    @Override
    public void release(String conversationId, String runId) {
        requireText(conversationId, "conversationId");
        requireText(runId, "runId");
        Long released = redisTemplate.execute(RELEASE_SCRIPT, List.of(lockKey(conversationId)), runId);
        log.info(
                "event=agent_conversation_lock_release traceId={} conversationId={} runId={} released={}",
                MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY),
                conversationId,
                runId,
                Long.valueOf(1L).equals(released)
        );
    }

    private String lockKey(String conversationId) {
        return KEY_PREFIX + conversationId + KEY_SUFFIX;
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }
}
