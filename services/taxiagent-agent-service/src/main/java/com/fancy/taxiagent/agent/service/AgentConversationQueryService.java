package com.fancy.taxiagent.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.taxiagent.agent.domain.dto.ConversationSummaryResponse;
import com.fancy.taxiagent.agent.domain.dto.MessageResponse;
import com.fancy.taxiagent.agent.domain.dto.PageResult;
import com.fancy.taxiagent.agent.domain.entity.AgentConversation;
import com.fancy.taxiagent.agent.domain.entity.AgentMessage;
import com.fancy.taxiagent.agent.domain.enums.ConversationStatus;
import com.fancy.taxiagent.agent.exception.AgentApiException;
import com.fancy.taxiagent.agent.mapper.AgentConversationMapper;
import com.fancy.taxiagent.agent.mapper.AgentMessageMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent 对话/消息的对外只读查询服务（列表与历史）。
 */
@Service
public class AgentConversationQueryService {

    /** 翻页上界：page 与 size 合法区间均为 [1, 50]。 */
    public static final int MAX_PAGE_SIZE = 50;

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;

    public AgentConversationQueryService(
            AgentConversationMapper conversationMapper,
            AgentMessageMapper messageMapper
    ) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    /**
     * 分页列出当前用户的对话，最近活跃在前。
     *
     * @param userId JWT subject 对应的用户 ID
     * @param page 页码（1 起）
     * @param size 页大小
     * @return 分页对话摘要
     */
    public PageResult<ConversationSummaryResponse> listConversations(long userId, int page, int size) {
        Long total = conversationMapper.selectCount(
                new LambdaQueryWrapper<AgentConversation>()
                        .eq(AgentConversation::getUserId, userId)
                        .ne(AgentConversation::getStatus, ConversationStatus.DELETED));
        List<ConversationSummaryResponse> records = conversationMapper.selectList(
                        new LambdaQueryWrapper<AgentConversation>()
                                .eq(AgentConversation::getUserId, userId)
                                .ne(AgentConversation::getStatus, ConversationStatus.DELETED)
                                .orderByDesc(AgentConversation::getUpdatedAt)
                                .last("LIMIT " + size + " OFFSET " + (long) (page - 1) * size))
                .stream()
                .map(entity -> new ConversationSummaryResponse(
                        entity.getConversationId(),
                        entity.getTitle(),
                        entity.getStatus(),
                        entity.getCreatedAt(),
                        entity.getUpdatedAt()))
                .toList();
        return new PageResult<>(page, size, total == null ? 0L : total, List.copyOf(records));
    }

    /**
     * 分页返回指定对话的消息历史（sequenceNo 升序）。
     *
     * <p>归属校验与写入路径 findOwnedActiveConversation 同语义：conversationId +
     * userId + ACTIVE 三条件同时命中才算本人活动对话。</p>
     *
     * @param userId JWT subject 对应的用户 ID
     * @param conversationId 对外对话 ID
     * @param page 页码（1 起）
     * @param size 页大小
     * @return 分页消息
     */
    public PageResult<MessageResponse> findMessages(long userId, String conversationId, int page, int size) {
        AgentConversation conversation = conversationMapper.selectOne(
                new LambdaQueryWrapper<AgentConversation>()
                        .eq(AgentConversation::getConversationId, conversationId)
                        .eq(AgentConversation::getUserId, userId)
                        .eq(AgentConversation::getStatus, ConversationStatus.ACTIVE)
                        .last("LIMIT 1"));
        if (conversation == null) {
            throw new AgentApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "对话不存在");
        }
        Long total = messageMapper.selectCount(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getConversationDbId, conversation.getId()));
        List<MessageResponse> records = messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                        .eq(AgentMessage::getConversationDbId, conversation.getId())
                        .orderByAsc(AgentMessage::getSequenceNo)
                        .last("LIMIT " + size + " OFFSET " + (long) (page - 1) * size))
                .stream()
                .map(this::toResponse)
                .toList();
        return new PageResult<>(page, size, total == null ? 0L : total, List.copyOf(records));
    }

    private MessageResponse toResponse(AgentMessage message) {
        return new MessageResponse(
                String.valueOf(message.getId()),
                message.getRole() == null ? null : message.getRole().name(),
                message.getContent(),
                message.getSequenceNo(),
                message.getCreatedAt());
    }

    /**
     * 校验并解析可选页码：缺省为 1，越界抛 400。
     */
    public int requireValidPage(Integer rawPage) {
        return requireInRange(rawPage, 1);
    }

    /**
     * 校验并解析可选页大小：缺省为给定默认值，越界抛 400。
     */
    public int requireValidSize(Integer rawSize, int defaultSize) {
        return requireInRange(rawSize, defaultSize);
    }

    private int requireInRange(Integer rawValue, int defaultValue) {
        if (rawValue == null) {
            return defaultValue;
        }
        if (rawValue < 1 || rawValue > MAX_PAGE_SIZE) {
            throw new AgentApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "分页参数不合法");
        }
        return rawValue;
    }
}
