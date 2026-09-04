package com.fancy.taxiagent.agent.state;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 对话轻量状态（Redis hash）：当前仅存分类标签，TTL 24 小时。
 */
@Service
public class ConversationStateService {

    private static final String PREFIX = "agent:chat:";
    private static final String CLASSIFICATION_FIELD = "classification";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    public ConversationStateService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 读取对话最近一次分类。
     *
     * @param conversationId 对外对话 ID
     * @return 分类标签；从未分类返回 null
     */
    public String getClassification(String conversationId) {
        Object value = redisTemplate.opsForHash().get(PREFIX + conversationId, CLASSIFICATION_FIELD);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 写入对话分类并刷新 TTL。
     *
     * @param conversationId 对外对话 ID
     * @param classification 分类标签
     */
    public void setClassification(String conversationId, String classification) {
        String key = PREFIX + conversationId;
        redisTemplate.opsForHash().put(key, CLASSIFICATION_FIELD, classification);
        redisTemplate.expire(key, TTL);
    }
}
