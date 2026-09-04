package com.fancy.taxiagent.ticket.domain.enums;

/**
 * 工单优先级：1-普通, 2-紧急, 3-特急
 */
public enum TicketPriority {
    NORMAL(1, "普通"),
    URGENT(2, "紧急"),
    CRITICAL(3, "特急");

    private final int code;
    private final String desc;

    TicketPriority(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static TicketPriority fromCode(int code) {
        for (TicketPriority priority : values()) {
            if (priority.code == code) {
                return priority;
            }
        }
        throw new IllegalArgumentException("Invalid ticket priority code: " + code);
    }
}
