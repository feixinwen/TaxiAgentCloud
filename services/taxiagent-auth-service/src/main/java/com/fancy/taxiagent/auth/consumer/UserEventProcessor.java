package com.fancy.taxiagent.auth.consumer;

import com.fancy.taxiagent.auth.domain.entity.AuthAccount;
import com.fancy.taxiagent.auth.service.AuthAccountService;
import com.fancy.taxiagent.auth.service.RefreshTokenService;
import com.fancy.taxiagent.auth.service.TokenRevocationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 用户领域事件处理器（与 MQ 解耦，可直接单测）。
 *
 * <p>幂等：Redis {@code auth:event:{eventId}} TTL 24h —— 处理成功后才认领去重键，
 * 已认领（hasKey 命中）的事件直接跳过（RocketMQ 为 at-least-once 投递，
 * 重试会产生重复消息）。撤销动作本身幂等，重复处理无害。</p>
 *
 * <p>USER_DISABLED / USER_DELETED / USER_ROLE_CHANGED → 撤销全部会话并自增 token_version
 * （DB 权威值与 Redis 校验副本同步更新，保证重新登录签发的新 Token 不被 Gateway 拒绝）；
 * USER_ACTIVATED → 无操作。解析或处理失败 → 记录日志后返回 true（ACK 丢弃，
 * 本阶段不做死信队列）；失败事件未认领去重键，会由 MQ 重新投递重试。</p>
 */
@Component
public class UserEventProcessor {

    private static final String EVENT_DEDUP_PREFIX = "auth:event:";
    private static final Duration DEDUP_TTL = Duration.ofHours(24);
    private static final Logger log = LoggerFactory.getLogger(UserEventProcessor.class);

    private final AuthAccountService authAccountService;
    private final RefreshTokenService refreshTokenService;
    private final TokenRevocationService tokenRevocationService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    public UserEventProcessor(@Lazy AuthAccountService authAccountService,
                              @Lazy RefreshTokenService refreshTokenService,
                              TokenRevocationService tokenRevocationService,
                              StringRedisTemplate redisTemplate) {
        this.authAccountService = authAccountService;
        this.refreshTokenService = refreshTokenService;
        this.tokenRevocationService = tokenRevocationService;
        this.redisTemplate = redisTemplate;
    }

    public boolean process(String messageBody) {
        try {
            UserEventPayload payload = objectMapper.readValue(messageBody, UserEventPayload.class);
            String dedupKey = EVENT_DEDUP_PREFIX + payload.eventId();
            if (Boolean.TRUE.equals(redisTemplate.hasKey(dedupKey))) {
                log.info("event=user_event_duplicate_skipped eventId={}", payload.eventId());
                return true;
            }
            switch (payload.eventType()) {
                case "USER_DISABLED", "USER_DELETED", "USER_ROLE_CHANGED" -> {
                    refreshTokenService.revokeAll(payload.userId());
                    bumpTokenVersion(payload.userId());
                    log.info("event=user_event_revoked userId={} type={}", payload.userId(), payload.eventType());
                }
                case "USER_ACTIVATED" -> log.info("event=user_event_activated_ignored userId={}", payload.userId());
                default -> log.warn("event=user_event_unknown_type type={}", payload.eventType());
            }
            // 仅在处理成功后才认领去重键：失败的撤销不认领，MQ at-least-once 会重新投递重试
            redisTemplate.opsForValue().set(dedupKey, "1", DEDUP_TTL);
            return true;
        } catch (Exception exception) {
            log.error("event=user_event_processing_failed dropped=true", exception);
            return true; // drop (documented; no dead-letter this phase)
        }
    }

    /**
     * 自增 token_version：先更新 DB 权威值，再同步 Redis 校验副本。
     * 账号已逻辑删除时仅更新 Redis（后续重新登录也走不到凭证查询）。
     */
    private void bumpTokenVersion(Long userId) {
        AuthAccount account = authAccountService.findActiveByUserId(userId);
        if (account == null) {
            tokenRevocationService.incrementTokenVersion(userId);
            return;
        }
        long next = account.getTokenVersion() + 1;
        account.setTokenVersion(next);
        authAccountService.updateCredentialState(account);
        tokenRevocationService.setTokenVersion(userId, next);
    }
}
