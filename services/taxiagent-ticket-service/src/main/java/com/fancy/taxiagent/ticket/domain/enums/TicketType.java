package com.fancy.taxiagent.ticket.domain.enums;

/**
 * 工单类型：1-物品遗失, 2-费用争议, 3-服务投诉, 4-安全问题, 5-其他
 */
public enum TicketType {
    LOST_ITEM(1, "物品遗失"),
    FARE_DISPUTE(2, "费用争议"),
    SERVICE_COMPLAINT(3, "服务投诉"),
    SAFETY_ISSUE(4, "安全问题"),
    OTHER(5, "其他");

    private final int code;
    private final String desc;

    TicketType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static TicketType fromCode(int code) {
        for (TicketType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid ticket type code: " + code);
    }
}
