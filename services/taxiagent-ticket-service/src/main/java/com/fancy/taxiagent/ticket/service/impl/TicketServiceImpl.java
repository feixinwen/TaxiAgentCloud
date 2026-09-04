package com.fancy.taxiagent.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.ticket.domain.dto.TicketCreateReqDTO;
import com.fancy.taxiagent.ticket.domain.dto.TicketQueryReqDTO;
import com.fancy.taxiagent.ticket.domain.entity.Ticket;
import com.fancy.taxiagent.ticket.domain.entity.TicketChat;
import com.fancy.taxiagent.ticket.domain.enums.TicketPriority;
import com.fancy.taxiagent.ticket.domain.enums.TicketSenderRole;
import com.fancy.taxiagent.ticket.domain.enums.TicketStatus;
import com.fancy.taxiagent.ticket.domain.enums.TicketType;
import com.fancy.taxiagent.ticket.domain.vo.PageResult;
import com.fancy.taxiagent.ticket.domain.vo.TicketChatVO;
import com.fancy.taxiagent.ticket.domain.vo.TicketDataVO;
import com.fancy.taxiagent.ticket.domain.vo.TicketDetailVO;
import com.fancy.taxiagent.ticket.domain.vo.TicketSimpleVO;
import com.fancy.taxiagent.ticket.domain.vo.TicketVO;
import com.fancy.taxiagent.ticket.exception.InvalidTicketRequestException;
import com.fancy.taxiagent.ticket.exception.TicketAccessDeniedException;
import com.fancy.taxiagent.ticket.exception.TicketNotFoundException;
import com.fancy.taxiagent.ticket.exception.TicketStateConflictException;
import com.fancy.taxiagent.ticket.id.TicketNumberGenerator;
import com.fancy.taxiagent.ticket.mapper.TicketChatMapper;
import com.fancy.taxiagent.ticket.mapper.TicketMapper;
import com.fancy.taxiagent.ticket.service.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 工单核心服务。业务规则与单体 TicketServiceImpl 一致；
 * 差异：身份来自参数（非 ThreadLocal）；越权统一 404；detail/chat 归属在 Service 层；
 * 统计缓存无分布式锁；sendMessage（单体死代码）不实现。
 */
@Service
public class TicketServiceImpl implements TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketServiceImpl.class);
    private static final String ACTION_REPLY = "REPLY";
    private static final String ACTION_RESOLVE = "RESOLVE";
    private static final String ACTION_TRANSFER = "TRANSFER";
    private static final String ACTION_REJECT = "REJECT";
    private static final long TICKET_STATS_CACHE_BASE_SECONDS = 20L;
    private static final int TICKET_STATS_CACHE_JITTER_SECONDS = 10;
    private static final String TICKET_STATS_CACHE_PREFIX = "ticket:statistics:";

    private final TicketMapper ticketMapper;
    private final TicketChatMapper ticketChatMapper;
    private final TicketNumberGenerator ticketNumberGenerator;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public TicketServiceImpl(TicketMapper ticketMapper, TicketChatMapper ticketChatMapper,
                             TicketNumberGenerator ticketNumberGenerator,
                             StringRedisTemplate stringRedisTemplate,
                             ObjectMapper objectMapper) {
        this.ticketMapper = ticketMapper;
        this.ticketChatMapper = ticketChatMapper;
        this.ticketNumberGenerator = ticketNumberGenerator;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    // ============ C 端 ============

    @Override
    @Transactional
    public String submitTicket(TicketCreateReqDTO req, Long userId) {
        if (userId == null) {
            throw new InvalidTicketRequestException("用户ID不能为空");
        }
        if (req.getTicketType() == null) {
            throw new InvalidTicketRequestException("工单类型不能为空");
        }
        if (!StringUtils.hasText(req.getTitle())) {
            throw new InvalidTicketRequestException("工单标题不能为空");
        }
        if (!StringUtils.hasText(req.getContent())) {
            throw new InvalidTicketRequestException("工单内容不能为空");
        }
        if (req.getPriority() != null && req.getPriority() != TicketPriority.NORMAL.getCode()
                && TicketType.SAFETY_ISSUE.getCode() != req.getTicketType()) {
            throw new InvalidTicketRequestException("非安全问题类型不允许设置高优先级");
        }

        String ticketId = ticketNumberGenerator.nextId(req.getTicketType());
        LocalDateTime now = LocalDateTime.now();
        Ticket ticket = new Ticket();
        ticket.setTicketId(ticketId);
        ticket.setUserId(userId);
        ticket.setUserType(req.getUserType() != null ? req.getUserType() : 1);
        ticket.setOrderId(req.getOrderId());
        ticket.setTicketType(req.getTicketType());
        ticket.setPriority(req.getPriority() != null ? req.getPriority() : TicketPriority.NORMAL.getCode());
        ticket.setTicketStatus(TicketStatus.PENDING_ASSIGN.getCode());
        ticket.setTitle(req.getTitle());
        ticket.setContent(req.getContent());
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);
        ticketMapper.insert(ticket);

        insertSystemMessage(ticketId, "工单已创建，等待客服处理");
        log.info("event=ticket_created ticketId={} userId={}", ticketId, userId);
        return ticketId;
    }

    @Override
    @Transactional
    public boolean cancelTicket(String ticketId, Long userId) {
        Ticket ticket = getRequiredTicket(ticketId);
        assertOwned(ticket, userId);
        if (ticket.getTicketStatus().equals(TicketStatus.CLOSED.getCode())) {
            throw new InvalidTicketRequestException("工单已关闭，无法重复操作");
        }
        int rows = ticketMapper.update(null, new LambdaUpdateWrapper<Ticket>()
                .eq(Ticket::getTicketId, ticketId)
                .set(Ticket::getTicketStatus, TicketStatus.CLOSED.getCode())
                .set(Ticket::getUpdatedAt, LocalDateTime.now()));
        if (rows > 0) {
            insertChat(ticketId, userId, TicketSenderRole.SYSTEM.getCode(), "用户已关闭工单");
        }
        return rows > 0;
    }

    @Override
    @Transactional
    public boolean confirmAndRate(String ticketId, Boolean satisfied, Integer rating,
                                  String feedbackContent, Long userId) {
        Ticket ticket = getRequiredTicket(ticketId);
        assertOwned(ticket, userId);
        if (!ticket.getTicketStatus().equals(TicketStatus.WAIT_USER_CONFIRM.getCode())) {
            throw new InvalidTicketRequestException("当前状态不允许确认结单");
        }
        int rows = ticketMapper.update(null, new LambdaUpdateWrapper<Ticket>()
                .eq(Ticket::getTicketId, ticketId)
                .set(Ticket::getTicketStatus, TicketStatus.COMPLETED.getCode())
                .set(Ticket::getUpdatedAt, LocalDateTime.now()));
        if (rows > 0) {
            String feedbackMsg = String.format("用户确认结单 | 满意度: %s | 评分: %d | 评价: %s",
                    Boolean.TRUE.equals(satisfied) ? "满意" : "不满意",
                    rating != null ? rating : 0,
                    StringUtils.hasText(feedbackContent) ? feedbackContent : "无");
            insertChat(ticketId, userId, TicketSenderRole.SYSTEM.getCode(), feedbackMsg);
        }
        return rows > 0;
    }

    @Override
    public PageResult<TicketVO> getUserTicketPage(Long userId, TicketQueryReqDTO req) {
        Page<Ticket> page = new Page<>(defaultPage(req.getPage()), defaultSize(req.getSize()));
        LambdaQueryWrapper<Ticket> qw = new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getUserId, userId);
        applyQueryFilters(qw, req);
        qw.orderByDesc(Ticket::getUpdatedAt);
        return toPage(ticketMapper.selectPage(page, qw));
    }

    @Override
    public TicketDetailVO getTicketDetail(String ticketId, Long operatorId, String operatorRole) {
        Ticket ticket = getRequiredTicket(ticketId);
        assertReadable(ticket, operatorId, operatorRole);
        return toDetailVO(ticket, getChatHistoryInternal(ticketId));
    }

    @Override
    public List<TicketChatVO> getChatHistory(String ticketId, Long operatorId, String operatorRole) {
        Ticket ticket = getRequiredTicket(ticketId);
        assertReadable(ticket, operatorId, operatorRole);
        return getChatHistoryInternal(ticketId);
    }

    @Override
    public List<TicketSimpleVO> getUnfinishedTickets(Long userId, Integer limit) {
        LambdaQueryWrapper<Ticket> qw = new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getUserId, userId)
                .notIn(Ticket::getTicketStatus, TicketStatus.COMPLETED.getCode(), TicketStatus.CLOSED.getCode())
                .orderByDesc(Ticket::getUpdatedAt)
                .last("LIMIT " + (limit != null && limit > 0 ? limit : 10));
        return ticketMapper.selectList(qw).stream().map(this::toSimpleVO).toList();
    }

    @Override
    public TicketDetailVO getLatestUnfinishedTicket(Long userId) {
        Ticket ticket = ticketMapper.selectOne(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getUserId, userId)
                .notIn(Ticket::getTicketStatus, TicketStatus.COMPLETED.getCode(), TicketStatus.CLOSED.getCode())
                .orderByDesc(Ticket::getUpdatedAt)
                .last("LIMIT 1"));
        if (ticket == null) {
            return null;
        }
        return toDetailVO(ticket, getChatHistoryInternal(ticket.getTicketId()));
    }

    @Override
    @Transactional
    public boolean appendUserMessage(String ticketId, String content, Long userId) {
        Ticket ticket = getRequiredTicket(ticketId);
        assertOwned(ticket, userId);
        if (ticket.getTicketStatus().equals(TicketStatus.COMPLETED.getCode())
                || ticket.getTicketStatus().equals(TicketStatus.CLOSED.getCode())) {
            throw new InvalidTicketRequestException("工单已完结，无法补充信息");
        }
        int senderRole = ticket.getUserType() == 1
                ? TicketSenderRole.PASSENGER.getCode()
                : TicketSenderRole.DRIVER.getCode();
        insertChat(ticketId, userId, senderRole, content);
        touch(ticketId);
        return true;
    }

    @Override
    @Transactional
    public boolean escalateTicket(String ticketId, Integer targetLevel, String reason, Long userId) {
        Ticket ticket = getRequiredTicket(ticketId);
        assertOwned(ticket, userId);
        int rows = applyEscalation(ticket, targetLevel);
        if (rows > 0) {
            insertSystemMessage(ticketId, String.format("工单已升级为【%s】级别，原因：%s",
                    targetLevel == 2 ? "紧急" : "特急", reason));
        }
        return rows > 0;
    }

    // ============ B 端 ============

    @Override
    public PageResult<TicketVO> getAdminTicketPage(TicketQueryReqDTO req) {
        Page<Ticket> page = new Page<>(defaultPage(req.getPage()), defaultSize(req.getSize()));
        LambdaQueryWrapper<Ticket> qw = new LambdaQueryWrapper<>();
        if (req.getHandlerId() != null) {
            qw.eq(Ticket::getHandlerId, req.getHandlerId());
        }
        applyQueryFilters(qw, req);
        qw.orderByDesc(Ticket::getPriority).orderByDesc(Ticket::getUpdatedAt);
        return toPage(ticketMapper.selectPage(page, qw));
    }

    @Override
    @Transactional
    public boolean assignTicket(String ticketId, Long targetHandlerId, Long operatorId, String operatorRole) {
        Ticket ticket = getRequiredTicket(ticketId);
        if (!ticket.getTicketStatus().equals(TicketStatus.PENDING_ASSIGN.getCode())) {
            throw new TicketStateConflictException("工单已被认领或已处理");
        }
        if ("SUPPORT".equals(operatorRole) && !targetHandlerId.equals(operatorId)) {
            throw new InvalidTicketRequestException("客服只能认领工单，不能分配工单");
        }
        int rows = ticketMapper.update(null, new LambdaUpdateWrapper<Ticket>()
                .eq(Ticket::getTicketId, ticketId)
                .eq(Ticket::getTicketStatus, TicketStatus.PENDING_ASSIGN.getCode())
                .set(Ticket::getHandlerId, targetHandlerId)
                .set(Ticket::getTicketStatus, TicketStatus.PROCESSING.getCode())
                .set(Ticket::getUpdatedAt, LocalDateTime.now()));
        if (rows <= 0) {
            throw new TicketStateConflictException("工单已被其他客服认领");
        }
        insertChat(ticketId, targetHandlerId, TicketSenderRole.SYSTEM.getCode(), "客服已接单，正在处理中");
        return true;
    }

    @Override
    @Transactional
    public boolean reassignTicket(String ticketId, Long targetHandlerId) {
        Ticket ticket = getRequiredTicket(ticketId);
        if (ticket.getTicketStatus().equals(TicketStatus.COMPLETED.getCode())
                || ticket.getTicketStatus().equals(TicketStatus.CLOSED.getCode())) {
            throw new InvalidTicketRequestException("工单已处理完成，无法转交");
        }
        if (ticket.getHandlerId() != null && ticket.getHandlerId().equals(targetHandlerId)) {
            throw new InvalidTicketRequestException("无法再分配给当前处理人");
        }
        boolean unHandled = ticket.getTicketStatus().equals(TicketStatus.PENDING_ASSIGN.getCode());
        int rows = ticketMapper.update(null, new LambdaUpdateWrapper<Ticket>()
                .eq(Ticket::getTicketId, ticketId)
                .set(Ticket::getHandlerId, targetHandlerId)
                .set(Ticket::getTicketStatus, TicketStatus.PROCESSING.getCode())
                .set(Ticket::getUpdatedAt, LocalDateTime.now()));
        if (rows > 0) {
            insertSystemMessage(ticketId, String.format(
                    "您的工单已被转交给一位客服处理，新工作人员会%s处理您的工单请求。",
                    unHandled ? "开始" : "继续"));
        }
        return rows > 0;
    }

    @Override
    @Transactional
    public boolean processTicket(String ticketId, String actionType, String content, Long operatorId) {
        Ticket ticket = getRequiredTicket(ticketId);
        if (ticket.getHandlerId() == null) {
            throw new InvalidTicketRequestException("工单未分配，无法处理");
        }
        if (!ticket.getHandlerId().equals(operatorId)) {
            throw new TicketAccessDeniedException("无权处理此工单");
        }
        LocalDateTime now = LocalDateTime.now();
        switch (actionType) {
            case ACTION_REPLY -> {
                insertChat(ticketId, operatorId, TicketSenderRole.CUSTOMER_SERVICE.getCode(), content);
                touch(ticketId);
            }
            case ACTION_RESOLVE -> {
                ticketMapper.update(null, new LambdaUpdateWrapper<Ticket>()
                        .eq(Ticket::getTicketId, ticketId)
                        .set(Ticket::getTicketStatus, TicketStatus.WAIT_USER_CONFIRM.getCode())
                        .set(Ticket::getProcessResult, content)
                        .set(Ticket::getUpdatedAt, now));
                insertChat(ticketId, operatorId, TicketSenderRole.CUSTOMER_SERVICE.getCode(), content);
                insertSystemMessage(ticketId, "客服已处理完成，等待用户确认");
            }
            case ACTION_TRANSFER -> {
                ticketMapper.update(null, new LambdaUpdateWrapper<Ticket>()
                        .eq(Ticket::getTicketId, ticketId)
                        .set(Ticket::getHandlerId, null)
                        .set(Ticket::getTicketStatus, TicketStatus.PENDING_ASSIGN.getCode())
                        .set(Ticket::getUpdatedAt, now));
                insertChat(ticketId, operatorId, TicketSenderRole.CUSTOMER_SERVICE.getCode(), content);
                insertSystemMessage(ticketId, "工单已转交，等待其他客服处理");
            }
            case ACTION_REJECT -> {
                ticketMapper.update(null, new LambdaUpdateWrapper<Ticket>()
                        .eq(Ticket::getTicketId, ticketId)
                        .set(Ticket::getTicketStatus, TicketStatus.CLOSED.getCode())
                        .set(Ticket::getProcessResult, "驳回: " + content)
                        .set(Ticket::getUpdatedAt, now));
                insertChat(ticketId, operatorId, TicketSenderRole.CUSTOMER_SERVICE.getCode(), content);
                insertSystemMessage(ticketId, "工单已驳回关闭");
            }
            default -> throw new InvalidTicketRequestException("未知的操作类型: " + actionType);
        }
        return true;
    }

    @Override
    @Transactional
    public boolean escalateTicketByAdmin(String ticketId, Integer targetLevel, String reason, Long operatorId) {
        Ticket ticket = getRequiredTicket(ticketId);
        int rows = applyEscalation(ticket, targetLevel);
        if (rows > 0) {
            insertSystemMessage(ticketId, String.format(
                    "工单已升级为【%s】级别（由%s操作），原因：%s",
                    targetLevel == 2 ? "紧急" : "特急", operatorId, reason));
        }
        return rows > 0;
    }

    @Override
    public TicketDataVO getTicketStatistics() {
        LocalDate bizDate = LocalDate.now();
        String cacheKey = TICKET_STATS_CACHE_PREFIX + bizDate;
        TicketDataVO cached = getCachedStatistics(cacheKey);
        if (cached != null) {
            return cached;
        }
        TicketDataVO fresh = queryStatisticsFromDb(bizDate);
        cacheStatistics(cacheKey, fresh);
        return fresh;
    }

    // ============ 私有辅助 ============

    private int applyEscalation(Ticket ticket, Integer targetLevel) {
        if (ticket.getTicketStatus().equals(TicketStatus.COMPLETED.getCode())
                || ticket.getTicketStatus().equals(TicketStatus.CLOSED.getCode())) {
            throw new InvalidTicketRequestException("工单已完结，无法升级");
        }
        if (targetLevel == null || targetLevel < 2 || targetLevel > 3) {
            throw new InvalidTicketRequestException("目标级别无效，仅支持2-紧急或3-特急");
        }
        if (ticket.getPriority() >= targetLevel) {
            throw new InvalidTicketRequestException("当前工单优先级已达到或超过目标级别");
        }
        return ticketMapper.update(null, new LambdaUpdateWrapper<Ticket>()
                .eq(Ticket::getTicketId, ticket.getTicketId())
                .set(Ticket::getPriority, targetLevel)
                .set(Ticket::getUpdatedAt, LocalDateTime.now()));
    }

    private Ticket getRequiredTicket(String ticketId) {
        Ticket ticket = ticketMapper.selectOne(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTicketId, ticketId));
        if (ticket == null) {
            throw new TicketNotFoundException("工单不存在");
        }
        return ticket;
    }

    private void assertOwned(Ticket ticket, Long userId) {
        if (ticket.getUserId() == null || !ticket.getUserId().equals(userId)) {
            throw new TicketNotFoundException("工单不存在");
        }
    }

    private void assertReadable(Ticket ticket, Long operatorId, String operatorRole) {
        if ("ADMIN".equals(operatorRole) || "SUPPORT".equals(operatorRole)) {
            return;
        }
        assertOwned(ticket, operatorId);
    }

    private List<TicketChatVO> getChatHistoryInternal(String ticketId) {
        List<TicketChat> chats = ticketChatMapper.selectList(new LambdaQueryWrapper<TicketChat>()
                .eq(TicketChat::getTicketId, ticketId)
                .orderByAsc(TicketChat::getCreatedAt));
        return chats.stream().map(this::toChatVO).toList();
    }

    private void insertChat(String ticketId, Long senderId, int senderRole, String content) {
        TicketChat chat = new TicketChat();
        chat.setTicketId(ticketId);
        chat.setSenderId(senderId);
        chat.setSenderRole(senderRole);
        chat.setContent(content);
        chat.setCreatedAt(LocalDateTime.now());
        ticketChatMapper.insert(chat);
    }

    private void insertSystemMessage(String ticketId, String content) {
        insertChat(ticketId, 0L, TicketSenderRole.SYSTEM.getCode(), content);
    }

    private void touch(String ticketId) {
        ticketMapper.update(null, new LambdaUpdateWrapper<Ticket>()
                .eq(Ticket::getTicketId, ticketId)
                .set(Ticket::getUpdatedAt, LocalDateTime.now()));
    }

    private void applyQueryFilters(LambdaQueryWrapper<Ticket> qw, TicketQueryReqDTO req) {
        if (req.getStatus() != null) {
            qw.eq(Ticket::getTicketStatus, req.getStatus());
        }
        if (req.getType() != null) {
            qw.eq(Ticket::getTicketType, req.getType());
        }
        if (req.getUserType() != null) {
            qw.eq(Ticket::getUserType, req.getUserType());
        }
        if (StringUtils.hasText(req.getKeyword())) {
            qw.and(w -> w.like(Ticket::getTitle, req.getKeyword())
                    .or().like(Ticket::getTicketId, req.getKeyword()));
        }
    }

    private PageResult<TicketVO> toPage(Page<Ticket> result) {
        List<TicketVO> records = result.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>((int) result.getCurrent(), (int) result.getSize(),
                result.getTotal(), records);
    }

    private TicketVO toVO(Ticket ticket) {
        return new TicketVO(
                ticket.getId() != null ? ticket.getId().toString() : null,
                ticket.getTicketId(),
                ticket.getUserId() != null ? ticket.getUserId().toString() : null,
                ticket.getUserType(),
                ticket.getUserType() != null && ticket.getUserType() == 1 ? "乘客" : "司机",
                ticket.getOrderId() != null ? ticket.getOrderId().toString() : null,
                ticket.getTicketType(), typeDesc(ticket.getTicketType()),
                ticket.getPriority(), priorityDesc(ticket.getPriority()),
                ticket.getTicketStatus(), statusDesc(ticket.getTicketStatus()),
                ticket.getHandlerId() != null ? ticket.getHandlerId().toString() : null,
                ticket.getTitle(), truncate(ticket.getContent(), 50),
                ticket.getCreatedAt(), ticket.getUpdatedAt());
    }

    private TicketDetailVO toDetailVO(Ticket ticket, List<TicketChatVO> chatHistory) {
        return new TicketDetailVO(
                ticket.getId() != null ? ticket.getId().toString() : null,
                ticket.getTicketId(),
                ticket.getUserId() != null ? ticket.getUserId().toString() : null,
                ticket.getUserType(),
                ticket.getUserType() != null && ticket.getUserType() == 1 ? "乘客" : "司机",
                ticket.getOrderId() != null ? ticket.getOrderId().toString() : null,
                ticket.getTicketType(), typeDesc(ticket.getTicketType()),
                ticket.getPriority(), priorityDesc(ticket.getPriority()),
                ticket.getTicketStatus(), statusDesc(ticket.getTicketStatus()),
                ticket.getHandlerId() != null ? ticket.getHandlerId().toString() : null,
                ticket.getTitle(), ticket.getContent(), ticket.getProcessResult(),
                ticket.getCreatedAt(), ticket.getUpdatedAt(), chatHistory);
    }

    private TicketSimpleVO toSimpleVO(Ticket ticket) {
        return new TicketSimpleVO(
                ticket.getTicketId(), ticket.getCreatedAt(),
                ticket.getTicketType(), typeDesc(ticket.getTicketType()),
                ticket.getTitle(),
                ticket.getTicketStatus(), statusDesc(ticket.getTicketStatus()));
    }

    private TicketChatVO toChatVO(TicketChat chat) {
        return new TicketChatVO(
                chat.getId() != null ? chat.getId().toString() : null,
                chat.getTicketId(),
                chat.getSenderId() != null ? chat.getSenderId().toString() : null,
                chat.getSenderRole(), senderRoleDesc(chat.getSenderRole()),
                chat.getContent(), chat.getCreatedAt());
    }

    private String typeDesc(Integer code) {
        if (code == null) {
            return "未知";
        }
        try {
            return TicketType.fromCode(code).getDesc();
        } catch (Exception e) {
            return "未知";
        }
    }

    private String priorityDesc(Integer code) {
        if (code == null) {
            return "普通";
        }
        try {
            return TicketPriority.fromCode(code).getDesc();
        } catch (Exception e) {
            return "普通";
        }
    }

    private String statusDesc(Integer code) {
        if (code == null) {
            return "未知";
        }
        try {
            return TicketStatus.fromCode(code).getDesc();
        } catch (Exception e) {
            return "未知";
        }
    }

    private String senderRoleDesc(Integer code) {
        if (code == null) {
            return "未知";
        }
        try {
            return TicketSenderRole.fromCode(code).getDesc();
        } catch (Exception e) {
            return "未知";
        }
    }

    private String truncate(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...";
    }

    private TicketDataVO queryStatisticsFromDb(LocalDate bizDate) {
        LocalDateTime todayStart = bizDate.atStartOfDay();
        LocalDateTime todayEnd = todayStart.plusDays(1);
        Long pending = ticketMapper.selectCount(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTicketStatus, TicketStatus.PENDING_ASSIGN.getCode()));
        Long processing = ticketMapper.selectCount(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTicketStatus, TicketStatus.PROCESSING.getCode())
                .isNotNull(Ticket::getHandlerId));
        Long created = ticketMapper.selectCount(new LambdaQueryWrapper<Ticket>()
                .ge(Ticket::getCreatedAt, todayStart).lt(Ticket::getCreatedAt, todayEnd));
        Long completed = ticketMapper.selectCount(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTicketStatus, TicketStatus.COMPLETED.getCode())
                .ge(Ticket::getUpdatedAt, todayStart).lt(Ticket::getUpdatedAt, todayEnd));
        return new TicketDataVO(
                pending == null ? 0L : pending,
                processing == null ? 0L : processing,
                created == null ? 0L : created,
                completed == null ? 0L : completed);
    }

    private TicketDataVO getCachedStatistics(String cacheKey) {
        String json = stringRedisTemplate.opsForValue().get(cacheKey);
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, TicketDataVO.class);
        } catch (Exception e) {
            log.warn("event=ticket_stats_cache_invalid key={}", cacheKey, e);
            stringRedisTemplate.delete(cacheKey);
            return null;
        }
    }

    private void cacheStatistics(String cacheKey, TicketDataVO data) {
        long ttl = TICKET_STATS_CACHE_BASE_SECONDS
                + ThreadLocalRandom.current().nextInt(TICKET_STATS_CACHE_JITTER_SECONDS + 1);
        try {
            stringRedisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(data),
                    ttl, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("event=ticket_stats_cache_write_failed key={}", cacheKey, e);
        }
    }

    private int defaultPage(Integer page) {
        return page == null || page <= 0 ? 1 : page;
    }

    private int defaultSize(Integer size) {
        return size == null || size <= 0 ? 10 : size;
    }
}
