package com.fancy.taxiagent.agent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fancy.taxiagent.agent.domain.enums.MessageRole;

import java.time.LocalDateTime;

/**
 * Agent Service 持有的用户与助手消息持久化实体。
 */
@TableName("agent_message")
public class AgentMessage {

    @TableId(type = IdType.INPUT)
    private Long id;

    private Long conversationDbId;

    private Long runDbId;

    private String clientMessageId;

    private MessageRole role;

    private String content;

    private Long sequenceNo;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getConversationDbId() {
        return conversationDbId;
    }

    public void setConversationDbId(Long conversationDbId) {
        this.conversationDbId = conversationDbId;
    }

    public Long getRunDbId() {
        return runDbId;
    }

    public void setRunDbId(Long runDbId) {
        this.runDbId = runDbId;
    }

    public String getClientMessageId() {
        return clientMessageId;
    }

    public void setClientMessageId(String clientMessageId) {
        this.clientMessageId = clientMessageId;
    }

    public MessageRole getRole() {
        return role;
    }

    public void setRole(MessageRole role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Long getSequenceNo() {
        return sequenceNo;
    }

    public void setSequenceNo(Long sequenceNo) {
        this.sequenceNo = sequenceNo;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
