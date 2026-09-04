package com.fancy.taxiagent.ticket.id;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TicketNumberGeneratorTest {

    private static final String TODAY = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    private static final String KEY = "ticket:no:" + TODAY;

    private StringRedisTemplate redisTemplate;
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOperations;
    private TicketNumberGenerator generator;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        generator = new TicketNumberGenerator(redisTemplate);
    }

    @Test
    void shouldGenerateFormattedId() {
        when(valueOperations.increment(KEY)).thenReturn(25L);

        String id = generator.nextId(1);

        assertThat(id).isEqualTo("T" + TODAY + "1" + "00025");
    }

    @Test
    void shouldSetExpiryWhenSequenceStarts() {
        when(valueOperations.increment(KEY)).thenReturn(1L);

        generator.nextId(1);

        verify(redisTemplate).expire(eq(KEY), eq(Duration.ofDays(2)));
    }

    @Test
    void shouldNotSetExpiryForLaterSequences() {
        when(valueOperations.increment(KEY)).thenReturn(2L);

        generator.nextId(1);

        verify(redisTemplate, never()).expire(eq(KEY), eq(Duration.ofDays(2)));
    }
}
