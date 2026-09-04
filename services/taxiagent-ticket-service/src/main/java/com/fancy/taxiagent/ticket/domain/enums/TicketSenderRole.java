package com.fancy.taxiagent.ticket.domain.enums;

/**
 * 聊天发送者角色：0-系统, 1-乘客, 2-司机, 3-客服
 */
public enum TicketSenderRole {
    SYSTEM(0, "系统自动"),
    PASSENGER(1, "乘客"),
    DRIVER(2, "司机"),
    CUSTOMER_SERVICE(3, "客服");

    private final int code;
    private final String desc;

    TicketSenderRole(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static TicketSenderRole fromCode(int code) {
        for (TicketSenderRole role : values()) {
            if (role.code == code) {
                return role;
            }
        }
        throw new IllegalArgumentException("Invalid ticket sender role code: " + code);
    }
}
