package com.fancy.taxiagent.agent.tool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 预约出发时间语义校验：必须是未来时间且不超过最大可预约跨度。
 *
 * <p>模型可能把"明天"等相对时间幻觉成过去日期（真实案例：2026-08-27 下单，
 * scheduled_time 落库为 2025-01-08），因此除格式校验外在工具层强制语义兜底。</p>
 */
public final class ScheduledTimePolicy {

    /** 最大可预约跨度（天）；拒绝文本与 OrderAgent 提示词口径需保持一致。 */
    public static final int MAX_ADVANCE_DAYS = 7;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private ScheduledTimePolicy() {
    }

    /**
     * 语义校验 scheduledTime 槽位值。
     *
     * @param raw 槽位原始值（yyyy-MM-dd HH:mm）
     * @param now 当前时间
     * @return 拒绝文本；合法返回 null
     */
    public static String rejectIfInvalid(String raw, LocalDateTime now) {
        LocalDateTime scheduled;
        try {
            scheduled = LocalDateTime.parse(raw, TIME_FORMAT);
        } catch (DateTimeParseException exception) {
            return "Tool execution failed: scheduledTime 格式必须为 yyyy-MM-dd HH:mm";
        }
        if (!scheduled.isAfter(now)) {
            return "Tool execution failed: scheduledTime " + raw + " 必须是未来的时间"
                    + "（当前时间 " + now.format(TIME_FORMAT) + "），请与用户确认正确的出发时间";
        }
        if (scheduled.isAfter(now.plusDays(MAX_ADVANCE_DAYS))) {
            return "Tool execution failed: scheduledTime 最多可预约 " + MAX_ADVANCE_DAYS
                    + " 天内的行程，请与用户确认正确的出发时间";
        }
        return null;
    }
}
