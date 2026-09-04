package com.fancy.taxiagent.ticket.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("sys_ticket")
public class Ticket {

    /** 主键ID（自增） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 工单对外编号（业务唯一标识） */
    @TableField("ticket_id")
    private String ticketId;

    /** 发起人ID */
    @TableField("user_id")
    private Long userId;

    /** 用户类型: 1-乘客, 2-司机 */
    @TableField("user_type")
    private Integer userType;

    /** 关联订单ID */
    @TableField("order_id")
    private Long orderId;

    /** 工单类型: 1-物品遗失, 2-费用争议, 3-服务投诉, 4-安全问题, 5-其他 */
    @TableField("ticket_type")
    private Integer ticketType;

    /** 优先级: 1-普通, 2-紧急, 3-特急 */
    @TableField("priority")
    private Integer priority;

    /** 状态: 0-待分配, 1-处理中, 2-待用户确认, 3-已完成, 4-已关闭 */
    @TableField("ticket_status")
    private Integer ticketStatus;

    /** 处理客服ID */
    @TableField("handler_id")
    private Long handlerId;

    /** 标题 */
    @TableField("title")
    private String title;

    /** 详情描述 */
    @TableField("content")
    private String content;

    /** 处理结果摘要 */
    @TableField("process_result")
    private String processResult;

    /** 创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTicketId() {
        return ticketId;
    }

    public void setTicketId(String ticketId) {
        this.ticketId = ticketId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getUserType() {
        return userType;
    }

    public void setUserType(Integer userType) {
        this.userType = userType;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Integer getTicketType() {
        return ticketType;
    }

    public void setTicketType(Integer ticketType) {
        this.ticketType = ticketType;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Integer getTicketStatus() {
        return ticketStatus;
    }

    public void setTicketStatus(Integer ticketStatus) {
        this.ticketStatus = ticketStatus;
    }

    public Long getHandlerId() {
        return handlerId;
    }

    public void setHandlerId(Long handlerId) {
        this.handlerId = handlerId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getProcessResult() {
        return processResult;
    }

    public void setProcessResult(String processResult) {
        this.processResult = processResult;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
