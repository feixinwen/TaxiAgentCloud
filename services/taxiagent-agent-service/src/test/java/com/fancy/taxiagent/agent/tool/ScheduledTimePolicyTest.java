package com.fancy.taxiagent.agent.tool;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证预约出发时间的语义校验：必须为未来、不超过最大可预约跨度、格式合法。
 */
class ScheduledTimePolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 28, 12, 0);

    @Test
    void shouldAcceptValidFutureTime() {
        assertThat(ScheduledTimePolicy.rejectIfInvalid("2026-09-01 10:00", NOW)).isNull();
    }

    @Test
    void shouldAcceptBoundaryExactlySevenDaysAhead() {
        assertThat(ScheduledTimePolicy.rejectIfInvalid("2026-09-04 12:00", NOW)).isNull();
    }

    @Test
    void shouldRejectPastTimeWithCurrentTimeHint() {
        String rejection = ScheduledTimePolicy.rejectIfInvalid("2020-01-01 09:00", NOW);

        assertThat(rejection).startsWith("Tool execution failed:")
                .contains("必须是未来的时间")
                .contains("2026-08-28 12:00");
    }

    @Test
    void shouldRejectTimeExactlyNow() {
        String rejection = ScheduledTimePolicy.rejectIfInvalid("2026-08-28 12:00", NOW);

        assertThat(rejection).contains("必须是未来的时间");
    }

    @Test
    void shouldRejectTimeBeyondSevenDays() {
        String rejection = ScheduledTimePolicy.rejectIfInvalid("2026-09-04 12:01", NOW);

        assertThat(rejection).startsWith("Tool execution failed:").contains("7 天");
    }

    @Test
    void shouldRejectMalformedFormat() {
        String rejection = ScheduledTimePolicy.rejectIfInvalid("2026/08/30 10:00", NOW);

        assertThat(rejection).isEqualTo("Tool execution failed: scheduledTime 格式必须为 yyyy-MM-dd HH:mm");
    }
}
