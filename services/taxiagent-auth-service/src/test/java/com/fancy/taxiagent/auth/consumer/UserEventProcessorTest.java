package com.fancy.taxiagent.auth.consumer;

import com.fancy.taxiagent.auth.domain.entity.AuthAccount;
import com.fancy.taxiagent.auth.service.AuthAccountService;
import com.fancy.taxiagent.auth.service.RefreshTokenService;
import com.fancy.taxiagent.auth.service.TokenRevocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserEventProcessorTest {

    private UserEventProcessor processor;
    private AuthAccountService authAccountService;
    private RefreshTokenService refreshTokenService;
    private TokenRevocationService tokenRevocationService;
    private StringRedisTemplate redisTemplate;
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        authAccountService = mock(AuthAccountService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        tokenRevocationService = mock(TokenRevocationService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        processor = new UserEventProcessor(authAccountService, refreshTokenService, tokenRevocationService, redisTemplate);
    }

    @Test
    void shouldRevokeOnDisableEventOnce() {
        String body = "{\"eventId\":\"evt-1\",\"userId\":90001,\"eventType\":\"USER_DISABLED\"}";
        when(redisTemplate.hasKey("auth:event:evt-1")).thenReturn(Boolean.FALSE);
        when(authAccountService.findActiveByUserId(90001L)).thenReturn(account(90001L, 0L));

        boolean handled = processor.process(body);

        assertThat(handled).isTrue();
        verify(refreshTokenService).revokeAll(90001L);
        // DB authoritative value bumped, then Redis synced to the same value
        verify(authAccountService).updateCredentialState(any(AuthAccount.class));
        verify(tokenRevocationService).setTokenVersion(90001L, 1L);
        verify(tokenRevocationService, never()).incrementTokenVersion(any());
        // dedup key claimed only after successful processing
        verify(valueOperations).set("auth:event:evt-1", "1", Duration.ofHours(24));
    }

    @Test
    void shouldSkipDuplicateEventId() {
        String body = "{\"eventId\":\"evt-2\",\"userId\":90002,\"eventType\":\"USER_DISABLED\"}";
        when(redisTemplate.hasKey("auth:event:evt-2")).thenReturn(Boolean.TRUE);

        boolean handled = processor.process(body);

        assertThat(handled).isTrue();
        verifyNoInteractions(refreshTokenService);
        verifyNoInteractions(tokenRevocationService);
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void shouldIgnoreActivatedEvent() {
        String body = "{\"eventId\":\"evt-3\",\"userId\":90003,\"eventType\":\"USER_ACTIVATED\"}";
        when(redisTemplate.hasKey("auth:event:evt-3")).thenReturn(Boolean.FALSE);

        boolean handled = processor.process(body);

        assertThat(handled).isTrue();
        verifyNoInteractions(refreshTokenService);
        verifyNoInteractions(tokenRevocationService);
        // no-op still claims the dedup key since processing succeeded
        verify(valueOperations).set("auth:event:evt-3", "1", Duration.ofHours(24));
    }

    @Test
    void shouldNotClaimDedupKeyWhenRevocationFails() {
        String body = "{\"eventId\":\"evt-4\",\"userId\":90004,\"eventType\":\"USER_DISABLED\"}";
        when(redisTemplate.hasKey("auth:event:evt-4")).thenReturn(Boolean.FALSE);
        doThrow(new RuntimeException("redis down")).when(refreshTokenService).revokeAll(90004L);

        boolean handled = processor.process(body);

        assertThat(handled).isTrue(); // dropped (ACK), redelivered by MQ at-least-once
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void shouldLogAndDropMalformedMessage() {
        boolean handled = processor.process("not-json");

        assertThat(handled).isTrue(); // dropped (ACK), no revocation
        verifyNoInteractions(refreshTokenService);
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void shouldStillRevokeForDeletedAccount() {
        String body = "{\"eventId\":\"evt-5\",\"userId\":90005,\"eventType\":\"USER_DELETED\"}";
        when(redisTemplate.hasKey("auth:event:evt-5")).thenReturn(Boolean.FALSE);
        when(authAccountService.findActiveByUserId(90005L)).thenReturn(null);

        boolean handled = processor.process(body);

        assertThat(handled).isTrue();
        verify(refreshTokenService).revokeAll(90005L);
        verify(tokenRevocationService).incrementTokenVersion(90005L);
        verify(authAccountService, never()).updateCredentialState(any());
    }

    private AuthAccount account(Long userId, Long tokenVersion) {
        AuthAccount account = new AuthAccount();
        account.setUserId(userId);
        account.setTokenVersion(tokenVersion);
        return account;
    }
}
