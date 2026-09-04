package com.fancy.taxiagent.order.domain.enums;

/**
 * 网约车订单状态枚举：10-创建, 20-司机接单, 30-司机到达, 40-行程中, 50-完成待支付, 60-已支付, 90-已取消
 */
public enum RideOrderStatus {
    CREATED(10, "创建"),
    DRIVER_ACCEPTED(20, "司机接单"),
    DRIVER_ARRIVED(30, "司机到达"),
    IN_TRIP(40, "行程中"),
    FINISHED_WAIT_PAY(50, "完成待支付"),
    PAID(60, "已支付"),
    CANCELLED(90, "已取消");

    /** 用户最大可取消状态（30=司机到达） */
    public static final int USER_MAX_CANCEL_STATUS = DRIVER_ARRIVED.getCode();
    /** 司机最小可取消状态（20=司机接单） */
    public static final int DRIVER_MIN_CANCEL_STATUS = DRIVER_ACCEPTED.getCode();

    private final int code;
    private final String desc;

    RideOrderStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static RideOrderStatus fromCode(int code) {
        for (RideOrderStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid order status code: " + code);
    }
}
