package com.fancy.taxiagent.agent.state;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对话状态服务：Redis hash 读写与 TTL 刷新。
 */
class ConversationStateServiceTest {

    private StringRedisTemplate redisTemplate;
    private HashOperations<String, Object, Object> hashOperations;
    private ConversationStateService stateService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        hashOperations = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        stateService = new ConversationStateService(redisTemplate);
    }

    @Test
    void shouldReturnClassificationFromRedisHash() {
        when(hashOperations.get("agent:chat:conv-1", "classification")).thenReturn("ORDER");

        assertThat(stateService.getClassification("conv-1")).isEqualTo("ORDER");
    }

    @Test
    void shouldReturnNullWhenNeverClassified() {
        when(hashOperations.get("agent:chat:conv-1", "classification")).thenReturn(null);

        assertThat(stateService.getClassification("conv-1")).isNull();
    }

    @Test
    void shouldStoreClassificationAndRefreshTtl() {
        stateService.setClassification("conv-1", "DAILY");

        verify(hashOperations).put("agent:chat:conv-1", "classification", "DAILY");
        verify(redisTemplate).expire("agent:chat:conv-1", Duration.ofHours(24));
    }
}
