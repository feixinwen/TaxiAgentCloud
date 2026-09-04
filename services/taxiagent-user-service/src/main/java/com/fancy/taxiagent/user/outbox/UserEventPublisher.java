package com.fancy.taxiagent.user.outbox;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.user.rocketmq.RocketMqProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用户领域事件发布器（outbox 模式）。
 *
 * <p>业务变更与 {@link #recordEvent} 写入处于同一本地事务；定时任务扫描 status=0 的行，
 * 发送到 RocketMQ 成功后标记 status=1，失败保持 status=0 留待下次重试。</p>
 */
@Service
public class UserEventPublisher {

    private static final String TOPIC = "user-events";
    private static final Logger log = LoggerFactory.getLogger(UserEventPublisher.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final UserEventOutboxMapper outboxMapper;
    private final RocketMqProducer producer;

    public UserEventPublisher(UserEventOutboxMapper outboxMapper, @Lazy RocketMqProducer producer) {
        this.outboxMapper = outboxMapper;
        this.producer = producer;
    }

    public void recordEvent(Long userId, UserEventType type, String payload) {
        UserEventOutbox event = new UserEventOutbox();
        event.setEventId(UUID.randomUUID().toString().replace("-", ""));
        event.setUserId(userId);
        event.setEventType(type.name());
        event.setPayload(payload);
        event.setStatus(0);
        outboxMapper.insert(event);
    }

    @Scheduled(fixedDelayString = "${taxiagent.rocketmq.publish-interval-ms:5000}")
    public int publishPending() {
        return publishPending(100);
    }

    public int publishPending(int limit) {
        List<UserEventOutbox> pending = outboxMapper.selectList(
                new LambdaQueryWrapper<UserEventOutbox>()
                        .eq(UserEventOutbox::getStatus, 0)
                        .orderByAsc(UserEventOutbox::getId)
                        .last("LIMIT " + limit));
        int published = 0;
        for (UserEventOutbox event : pending) {
            try {
                boolean ok = producer.send(TOPIC, event.getEventType(), event.getEventId(), messageBody(event));
                if (!ok) {
                    continue;
                }
            } catch (Exception exception) {
                log.warn("event=outbox_publish_failed eventId={} reason={}", event.getEventId(), exception.getMessage());
                continue;
            }
            UserEventOutbox update = new UserEventOutbox();
            update.setId(event.getId());
            update.setStatus(1);
            update.setPublishedAt(LocalDateTime.now());
            outboxMapper.updateById(update);
            published++;
        }
        return published;
    }

    /**
     * 组装投递给 RocketMQ 的完整事件体（含 eventId/userId/eventType，供消费端幂等与撤销）。
     * payload 存的是 {@code {"role":"X"}} 片段，合并进事件体。
     */
    private String messageBody(UserEventOutbox event) throws JsonProcessingException {
        String role = null;
        if (event.getPayload() != null) {
            Map<?, ?> parsed = OBJECT_MAPPER.readValue(event.getPayload(), Map.class);
            Object value = parsed.get("role");
            role = value == null ? null : value.toString();
        }
        return OBJECT_MAPPER.writeValueAsString(Map.of(
                "eventId", event.getEventId(),
                "userId", event.getUserId(),
                "eventType", event.getEventType(),
                "role", role
        ));
    }
}
