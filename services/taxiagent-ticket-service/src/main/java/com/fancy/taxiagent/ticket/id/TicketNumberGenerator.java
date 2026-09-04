package com.fancy.taxiagent.ticket.id;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 工单编号生成：T + yyyyMMdd + 类型码 + 当日序号(5位)，Redis 当日自增。
 */
@Component
public class TicketNumberGenerator {

    private static final String TICKET_NO_PREFIX = "ticket:no:";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Duration KEY_TTL = Duration.ofDays(2);

    private final StringRedisTemplate redisTemplate;

    public TicketNumberGenerator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String nextId(Integer ticketType) {
        String dateStr = LocalDate.now().format(DATE_FORMATTER);
        String redisKey = TICKET_NO_PREFIX + dateStr;
        Long seq = redisTemplate.opsForValue().increment(redisKey);
        if (seq != null && seq == 1L) {
            redisTemplate.expire(redisKey, KEY_TTL);
        }
        return String.format("T%s%s%05d", dateStr, ticketType, seq == null ? 0 : seq);
    }
}
