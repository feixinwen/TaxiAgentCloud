package com.fancy.taxiagent.order.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("sys_ride_order")
public class RideOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID（自增） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 订单ID（雪花ID，业务唯一标识） */
    @TableField("order_id")
    private Long orderId;

    /** 乘客用户ID */
    @TableField("user_id")
    private Long userId;

    /** 司机ID (接单后回填) */
    @TableField("driver_id")
    private Long driverId;

    /** 关联MongoDB的轨迹ID */
    @TableField("mongo_trace_id")
    private String mongoTraceId;

    /** 车辆类型 (1:快车, 2:优享, 3:专车) */
    @TableField("vehicle_type")
    private Integer vehicleType;

    /** 是否为预约单 (0:否, 1:是) */
    @TableField("is_reservation")
    private Integer isReservation;

    /** 是否加急 (0:否, 1:是) */
    @TableField("is_expedited")
    private Integer isExpedited;

    /** 安全码 (乘客上车核销用) */
    @TableField("safety_code")
    private String safetyCode;

    /** 状态: 10-创建, 20-司机接单, 30-司机到达, 40-行程中, 50-完成待支付, 60-已支付, 90-已取消 */
    @TableField("order_status")
    private Integer orderStatus;

    /** 取消方: 1-用户, 2-司机, 3-系统 */
    @TableField("cancel_role")
    private Integer cancelRole;

    /** 取消原因 */
    @TableField("cancel_reason")
    private String cancelReason;

    /** 下单时间 */
    @TableField("create_time")
    private LocalDateTime createTime;

    /** 预约上车时间 (仅预约单) */
    @TableField("scheduled_time")
    private LocalDateTime scheduledTime;

    /** 司机接单时间 */
    @TableField("driver_accept_time")
    private LocalDateTime driverAcceptTime;

    /** 司机到达起点时间 */
    @TableField("driver_arrive_time")
    private LocalDateTime driverArriveTime;

    /** 开始行程/上车时间 (计费开始) */
    @TableField("pickup_time")
    private LocalDateTime pickupTime;

    /** 结束行程/下车时间 (计费结束) */
    @TableField("finish_time")
    private LocalDateTime finishTime;

    /** 支付时间 */
    @TableField("pay_time")
    private LocalDateTime payTime;

    /** 起点地址 */
    @TableField("start_address")
    private String startAddress;

    /** 起点纬度 */
    @TableField("start_lat")
    private BigDecimal startLat;

    /** 起点经度 */
    @TableField("start_lng")
    private BigDecimal startLng;

    /** 终点地址 */
    @TableField("end_address")
    private String endAddress;

    /** 终点纬度 */
    @TableField("end_lat")
    private BigDecimal endLat;

    /** 终点经度 */
    @TableField("end_lng")
    private BigDecimal endLng;

    /** 预估距离 (Km) */
    @TableField("est_distance")
    private BigDecimal estDistance;

    /** 实际距离 (Km) */
    @TableField("real_distance")
    private BigDecimal realDistance;

    /** 预估一口价 */
    @TableField("est_price")
    private BigDecimal estPrice;

    /** 实际最终车费 */
    @TableField("real_price")
    private BigDecimal realPrice;

    /** 基础里程费 */
    @TableField("price_base")
    private BigDecimal priceBase;

    /** 时长费 */
    @TableField("price_time")
    private BigDecimal priceTime;

    /** 远途/空驶费 */
    @TableField("price_distance")
    private BigDecimal priceDistance;

    /** 加急费 */
    @TableField("price_expedited")
    private BigDecimal priceExpedited;

    /** 乘算比例 */
    @TableField("price_radio")
    private BigDecimal priceRadio;

    /** 更新时间 */
    @TableField("update_time")
    private LocalDateTime updateTime;

    /** 逻辑删除 */
    @TableField("is_deleted")
    private Integer isDeleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public String getMongoTraceId() {
        return mongoTraceId;
    }

    public void setMongoTraceId(String mongoTraceId) {
        this.mongoTraceId = mongoTraceId;
    }

    public Integer getVehicleType() {
        return vehicleType;
    }

    public void setVehicleType(Integer vehicleType) {
        this.vehicleType = vehicleType;
    }

    public Integer getIsReservation() {
        return isReservation;
    }

    public void setIsReservation(Integer isReservation) {
        this.isReservation = isReservation;
    }

    public Integer getIsExpedited() {
        return isExpedited;
    }

    public void setIsExpedited(Integer isExpedited) {
        this.isExpedited = isExpedited;
    }

    public String getSafetyCode() {
        return safetyCode;
    }

    public void setSafetyCode(String safetyCode) {
        this.safetyCode = safetyCode;
    }

    public Integer getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(Integer orderStatus) {
        this.orderStatus = orderStatus;
    }

    public Integer getCancelRole() {
        return cancelRole;
    }

    public void setCancelRole(Integer cancelRole) {
        this.cancelRole = cancelRole;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getScheduledTime() {
        return scheduledTime;
    }

    public void setScheduledTime(LocalDateTime scheduledTime) {
        this.scheduledTime = scheduledTime;
    }

    public LocalDateTime getDriverAcceptTime() {
        return driverAcceptTime;
    }

    public void setDriverAcceptTime(LocalDateTime driverAcceptTime) {
        this.driverAcceptTime = driverAcceptTime;
    }

    public LocalDateTime getDriverArriveTime() {
        return driverArriveTime;
    }

    public void setDriverArriveTime(LocalDateTime driverArriveTime) {
        this.driverArriveTime = driverArriveTime;
    }

    public LocalDateTime getPickupTime() {
        return pickupTime;
    }

    public void setPickupTime(LocalDateTime pickupTime) {
        this.pickupTime = pickupTime;
    }

    public LocalDateTime getFinishTime() {
        return finishTime;
    }

    public void setFinishTime(LocalDateTime finishTime) {
        this.finishTime = finishTime;
    }

    public LocalDateTime getPayTime() {
        return payTime;
    }

    public void setPayTime(LocalDateTime payTime) {
        this.payTime = payTime;
    }

    public String getStartAddress() {
        return startAddress;
    }

    public void setStartAddress(String startAddress) {
        this.startAddress = startAddress;
    }

    public BigDecimal getStartLat() {
        return startLat;
    }

    public void setStartLat(BigDecimal startLat) {
        this.startLat = startLat;
    }

    public BigDecimal getStartLng() {
        return startLng;
    }

    public void setStartLng(BigDecimal startLng) {
        this.startLng = startLng;
    }

    public String getEndAddress() {
        return endAddress;
    }

    public void setEndAddress(String endAddress) {
        this.endAddress = endAddress;
    }

    public BigDecimal getEndLat() {
        return endLat;
    }

    public void setEndLat(BigDecimal endLat) {
        this.endLat = endLat;
    }

    public BigDecimal getEndLng() {
        return endLng;
    }

    public void setEndLng(BigDecimal endLng) {
        this.endLng = endLng;
    }

    public BigDecimal getEstDistance() {
        return estDistance;
    }

    public void setEstDistance(BigDecimal estDistance) {
        this.estDistance = estDistance;
    }

    public BigDecimal getRealDistance() {
        return realDistance;
    }

    public void setRealDistance(BigDecimal realDistance) {
        this.realDistance = realDistance;
    }

    public BigDecimal getEstPrice() {
        return estPrice;
    }

    public void setEstPrice(BigDecimal estPrice) {
        this.estPrice = estPrice;
    }

    public BigDecimal getRealPrice() {
        return realPrice;
    }

    public void setRealPrice(BigDecimal realPrice) {
        this.realPrice = realPrice;
    }

    public BigDecimal getPriceBase() {
        return priceBase;
    }

    public void setPriceBase(BigDecimal priceBase) {
        this.priceBase = priceBase;
    }

    public BigDecimal getPriceTime() {
        return priceTime;
    }

    public void setPriceTime(BigDecimal priceTime) {
        this.priceTime = priceTime;
    }

    public BigDecimal getPriceDistance() {
        return priceDistance;
    }

    public void setPriceDistance(BigDecimal priceDistance) {
        this.priceDistance = priceDistance;
    }

    public BigDecimal getPriceExpedited() {
        return priceExpedited;
    }

    public void setPriceExpedited(BigDecimal priceExpedited) {
        this.priceExpedited = priceExpedited;
    }

    public BigDecimal getPriceRadio() {
        return priceRadio;
    }

    public void setPriceRadio(BigDecimal priceRadio) {
        this.priceRadio = priceRadio;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public Integer getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(Integer isDeleted) {
        this.isDeleted = isDeleted;
    }
}
