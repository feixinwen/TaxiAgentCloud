package com.fancy.taxiagent.agent.state;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单 HITL 状态服务：槽位、break 标志、防重复下单与 resume 注入点的 Redis hash 读写。
 */
class OrderStateServiceTest {

    private static final String CONVERSATION_ID = "conv-001";
    private static final String PREFIX = "agent:order:";

    private StringRedisTemplate redisTemplate;
    private HashOperations<String, Object, Object> hashOps;
    private OrderStateService stateService;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        stateService = new OrderStateService(redisTemplate);
    }

    @Test
    void shouldSetAndGetSlot() {
        stateService.setSlot(CONVERSATION_ID, "vehicleType", "1");
        verify(hashOps).put(PREFIX + CONVERSATION_ID, "vehicleType", "1");
        when(hashOps.get(PREFIX + CONVERSATION_ID, "vehicleType")).thenReturn("1");
        assertThat(stateService.getSlot(CONVERSATION_ID, "vehicleType")).isEqualTo("1");
    }

    @Test
    void shouldReturnNullForMissingSlot() {
        when(hashOps.get(PREFIX + CONVERSATION_ID, "startLat")).thenReturn(null);
        assertThat(stateService.getSlot(CONVERSATION_ID, "startLat")).isNull();
    }

    @Test
    void shouldGetAllSlots() {
        Map<String, String> slots = Map.of("vehicleType", "1", "startLat", "31.23");
        when(hashOps.entries(PREFIX + CONVERSATION_ID)).thenReturn(new HashMap<>(slots));
        assertThat(stateService.getAllSlots(CONVERSATION_ID)).containsExactlyInAnyOrderEntriesOf(slots);
    }

    @Test
    void shouldDeleteAllSlots() {
        stateService.deleteSlots(CONVERSATION_ID);
        verify(hashOps).delete(PREFIX + CONVERSATION_ID, (Object[]) new String[]{
                "vehicleType", "isReservation", "isExpedited", "scheduledTime",
                "startAddress", "startLat", "startLng", "endAddress", "endLat", "endLng"});
    }

    @Test
    void shouldSetAndCheckBreak() {
        stateService.setBreak(CONVERSATION_ID, true);
        verify(hashOps).put(PREFIX + CONVERSATION_ID, "break", "yes");
        when(hashOps.get(PREFIX + CONVERSATION_ID, "break")).thenReturn("yes");
        assertThat(stateService.isBreak(CONVERSATION_ID)).isTrue();
        stateService.setBreak(CONVERSATION_ID, false);
        verify(hashOps).put(PREFIX + CONVERSATION_ID, "break", "no");
    }

    @Test
    void shouldSetAndCheckOrderId() {
        stateService.setOrderId(CONVERSATION_ID, "ORDER123");
        verify(hashOps).put(PREFIX + CONVERSATION_ID, "orderId", "ORDER123");
        when(hashOps.get(PREFIX + CONVERSATION_ID, "orderId")).thenReturn("ORDER123");
        assertThat(stateService.isOrderCreated(CONVERSATION_ID)).isTrue();
    }

    @Test
    void shouldSetAndGetLastConfirmToolCallId() {
        stateService.setLastConfirmToolCallId(CONVERSATION_ID, "call-9");
        verify(hashOps).put(PREFIX + CONVERSATION_ID, "lastConfirmToolCallId", "call-9");
        when(hashOps.get(PREFIX + CONVERSATION_ID, "lastConfirmToolCallId")).thenReturn("call-9");
        assertThat(stateService.getLastConfirmToolCallId(CONVERSATION_ID)).isEqualTo("call-9");
    }

    @Test
    void shouldSetTtlOnEveryWrite() {
        stateService.setSlot(CONVERSATION_ID, "vehicleType", "1");
        verify(redisTemplate).expire(PREFIX + CONVERSATION_ID, Duration.ofHours(24));
    }
}
