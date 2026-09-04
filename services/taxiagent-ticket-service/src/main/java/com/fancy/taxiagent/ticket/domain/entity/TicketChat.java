package com.fancy.taxiagent.ticket.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("sys_ticket_chat")
public class TicketChat {

    /** 主键ID（自增） */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联工单编号 */
    @TableField("ticket_id")
    private String ticketId;

    /** 发送者ID (0=系统) */
    @TableField("sender_id")
    private Long senderId;

    /** 发送者角色: 0-系统, 1-乘客, 2-司机, 3-客服 */
    @TableField("sender_role")
    private Integer senderRole;

    /** 消息内容 */
    @TableField("content")
    private String content;

    /** 发送时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

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

    public Long getSenderId() {
        return senderId;
    }

    public void setSenderId(Long senderId) {
        this.senderId = senderId;
    }

    public Integer getSenderRole() {
        return senderRole;
    }

    public void setSenderRole(Integer senderRole) {
        this.senderRole = senderRole;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
