package com.fancy.taxiagent.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.taxiagent.ticket.domain.dto.TicketCreateReqDTO;
import com.fancy.taxiagent.ticket.domain.dto.TicketQueryReqDTO;
import com.fancy.taxiagent.ticket.domain.entity.Ticket;
import com.fancy.taxiagent.ticket.domain.entity.TicketChat;
import com.fancy.taxiagent.ticket.domain.enums.TicketStatus;
import com.fancy.taxiagent.ticket.domain.vo.PageResult;
import com.fancy.taxiagent.ticket.domain.vo.TicketChatVO;
import com.fancy.taxiagent.ticket.domain.vo.TicketDataVO;
import com.fancy.taxiagent.ticket.domain.vo.TicketDetailVO;
import com.fancy.taxiagent.ticket.domain.vo.TicketVO;
import com.fancy.taxiagent.ticket.exception.InvalidTicketRequestException;
import com.fancy.taxiagent.ticket.exception.TicketNotFoundException;
import com.fancy.taxiagent.ticket.exception.TicketStateConflictException;
import com.fancy.taxiagent.ticket.mapper.TicketChatMapper;
import com.fancy.taxiagent.ticket.mapper.TicketMapper;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工单核心链路集成测试（真实 MySQL/Redis 容器 + Flyway）。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false"
        }
)
class TicketServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("taxiagent_ticket");

    @Container
    @ServiceConnection
    static final RedisContainer REDIS = new RedisContainer("redis:7-alpine");

    @Autowired
    private TicketService ticketService;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private TicketChatMapper ticketChatMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void clean() {
        ticketChatMapper.delete(null);
        ticketMapper.delete(null);
        Set<String> keys = redisTemplate.keys("ticket:*");
        if (keys != null) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void contextLoadsAndFlywayApplied() {
        assertThat(redisTemplate.getConnectionFactory()).isNotNull();
        assertThat(ticketMapper.selectCount(null)).isZero();
    }

    @Test
    void shouldSubmitAndFormatTicketId() {
        String ticketId = ticketService.submitTicket(createReq(1, 1, "丢了手机", "在车上丢了手机"), 90001L);

        assertThat(ticketId).matches("T\\d{8}1\\d{5}");
        Ticket ticket = findByTicketId(ticketId);
        assertThat(ticket.getTicketStatus()).isEqualTo(TicketStatus.PENDING_ASSIGN.getCode());
        assertThat(ticket.getUserId()).isEqualTo(90001L);
        List<TicketChat> chats = ticketChats(ticketId);
        assertThat(chats).hasSize(1);
        assertThat(chats.get(0).getSenderRole()).isZero();
        assertThat(chats.get(0).getContent()).contains("等待客服处理");
    }

    @Test
    void shouldRejectHighPriorityForNonSafetyType() {
        assertThatThrownBy(() -> ticketService.submitTicket(createReq(2, 2, "费用争议", "多扣钱了"), 90001L))
                .isInstanceOf(InvalidTicketRequestException.class);
    }

    @Test
    void shouldRejectIncompleteSubmission() {
        assertThatThrownBy(() -> ticketService.submitTicket(new TicketCreateReqDTO(), 90001L))
                .isInstanceOf(InvalidTicketRequestException.class);
    }

    @Test
    void shouldAssignWithOptimisticLockAndRejectConcurrentAssign() {
        String ticketId = ticketService.submitTicket(createReq(1, 1, "t", "c"), 90001L);

        boolean assigned = ticketService.assignTicket(ticketId, 70001L, 70001L, "SUPPORT");
        assertThat(assigned).isTrue();
        assertThat(findByTicketId(ticketId).getTicketStatus()).isEqualTo(TicketStatus.PROCESSING.getCode());

        assertThatThrownBy(() -> ticketService.assignTicket(ticketId, 70002L, 70002L, "SUPPORT"))
                .isInstanceOf(TicketStateConflictException.class);
    }

    @Test
    void shouldRejectSupportAssigningToOthers() {
        String ticketId = ticketService.submitTicket(createReq(1, 1, "t", "c"), 90001L);

        assertThatThrownBy(() -> ticketService.assignTicket(ticketId, 70002L, 70001L, "SUPPORT"))
                .isInstanceOf(InvalidTicketRequestException.class);
    }

    @Test
    void shouldProcessResolveThenConfirmFullFlow() {
        String ticketId = ticketService.submitTicket(createReq(1, 1, "t", "c"), 90001L);
        ticketService.assignTicket(ticketId, 70001L, 70001L, "SUPPORT");

        ticketService.processTicket(ticketId, "REPLY", "已收到，正在核实", 70001L);
        assertThat(findByTicketId(ticketId).getTicketStatus()).isEqualTo(TicketStatus.PROCESSING.getCode());

        ticketService.processTicket(ticketId, "RESOLVE", "已退款到原账户", 70001L);
        assertThat(findByTicketId(ticketId).getTicketStatus()).isEqualTo(TicketStatus.WAIT_USER_CONFIRM.getCode());
        assertThat(findByTicketId(ticketId).getProcessResult()).isEqualTo("已退款到原账户");

        ticketService.confirmAndRate(ticketId, true, 5, "服务很好", 90001L);
        assertThat(findByTicketId(ticketId).getTicketStatus()).isEqualTo(TicketStatus.COMPLETED.getCode());
        List<TicketChatVO> chats = ticketService.getChatHistory(ticketId, 90001L, "USER");
        assertThat(chats.get(chats.size() - 1).content()).contains("用户确认结单");
    }

    @Test
    void shouldRejectProcessByNonHandler() {
        String ticketId = ticketService.submitTicket(createReq(1, 1, "t", "c"), 90001L);
        ticketService.assignTicket(ticketId, 70001L, 70001L, "SUPPORT");

        assertThatThrownBy(() -> ticketService.processTicket(ticketId, "REPLY", "hi", 70002L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void shouldRejectProcessBeforeAssign() {
        String ticketId = ticketService.submitTicket(createReq(1, 1, "t", "c"), 90001L);

        assertThatThrownBy(() -> ticketService.processTicket(ticketId, "REPLY", "hi", 70001L))
                .isInstanceOf(InvalidTicketRequestException.class);
    }

    @Test
    void shouldCancelTicket() {
        String ticketId = ticketService.submitTicket(createReq(1, 1, "t", "c"), 90001L);

        ticketService.cancelTicket(ticketId, 90001L);

        assertThat(findByTicketId(ticketId).getTicketStatus()).isEqualTo(TicketStatus.CLOSED.getCode());
        List<TicketChat> chats = ticketChats(ticketId);
        assertThat(chats.get(chats.size() - 1).getContent()).isEqualTo("用户已关闭工单");
    }

    @Test
    void shouldRejectOthersTicket() {
        String ticketId = ticketService.submitTicket(createReq(1, 1, "t", "c"), 90001L);

        assertThatThrownBy(() -> ticketService.cancelTicket(ticketId, 90002L))
                .isInstanceOf(TicketNotFoundException.class);
        assertThatThrownBy(() -> ticketService.getTicketDetail(ticketId, 90002L, "USER"))
                .isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    void shouldAllowAdminReadAnyTicket() {
        String ticketId = ticketService.submitTicket(createReq(1, 1, "t", "c"), 90001L);

        TicketDetailVO detail = ticketService.getTicketDetail(ticketId, 50000L, "ADMIN");
        assertThat(detail.ticketId()).isEqualTo(ticketId);
    }

    @Test
    void shouldAppendAndRejectAfterCompleted() {
        String ticketId = ticketService.submitTicket(createReq(1, 1, "t", "c"), 90001L);

        ticketService.appendUserMessage(ticketId, "补充：手机是黑色", 90001L);
        List<TicketChat> chats = ticketChats(ticketId);
        assertThat(chats.get(chats.size() - 1).getContent()).isEqualTo("补充：手机是黑色");

        ticketService.assignTicket(ticketId, 70001L, 70001L, "SUPPORT");
        ticketService.processTicket(ticketId, "RESOLVE", "已处理", 70001L);
        ticketService.confirmAndRate(ticketId, true, 5, "ok", 90001L);

        assertThatThrownBy(() -> ticketService.appendUserMessage(ticketId, "追加", 90001L))
                .isInstanceOf(InvalidTicketRequestException.class);
    }

    @Test
    void shouldEscalatePriority() {
        String ticketId = ticketService.submitTicket(createReq(4, 1, "安全", "司机危险驾驶"), 90001L);

        ticketService.escalateTicket(ticketId, 2, "司机危险驾驶", 90001L);
        assertThat(findByTicketId(ticketId).getPriority()).isEqualTo(2);

        assertThatThrownBy(() -> ticketService.escalateTicket(ticketId, 2, "再升", 90001L))
                .isInstanceOf(InvalidTicketRequestException.class);
    }

    @Test
    void shouldTransferBackToPoolAndReject() {
        String ticketId = ticketService.submitTicket(createReq(1, 1, "t", "c"), 90001L);
        ticketService.assignTicket(ticketId, 70001L, 70001L, "SUPPORT");

        ticketService.processTicket(ticketId, "TRANSFER", "转交", 70001L);
        Ticket ticket = findByTicketId(ticketId);
        assertThat(ticket.getTicketStatus()).isEqualTo(TicketStatus.PENDING_ASSIGN.getCode());
        assertThat(ticket.getHandlerId()).isNull();
    }

    @Test
    void shouldPageOwnAndAdminTickets() {
        ticketService.submitTicket(createReq(1, 1, "a", "c"), 90001L);
        ticketService.submitTicket(createReq(1, 1, "b", "c"), 90001L);
        ticketService.submitTicket(createReq(1, 1, "c", "c"), 90002L);

        PageResult<TicketVO> own = ticketService.getUserTicketPage(90001L, new TicketQueryReqDTO());
        assertThat(own.total()).isEqualTo(2);
        PageResult<TicketVO> admin = ticketService.getAdminTicketPage(new TicketQueryReqDTO());
        assertThat(admin.total()).isEqualTo(3);
    }

    @Test
    void shouldListUnfinishedAndLatest() {
        String ticketId = ticketService.submitTicket(createReq(1, 1, "t", "c"), 90001L);

        assertThat(ticketService.getUnfinishedTickets(90001L, null)).hasSize(1);
        assertThat(ticketService.getLatestUnfinishedTicket(90001L).ticketId()).isEqualTo(ticketId);

        ticketService.cancelTicket(ticketId, 90001L);
        assertThat(ticketService.getUnfinishedTickets(90001L, null)).isEmpty();
    }

    @Test
    void shouldComputeStatistics() {
        String t1 = ticketService.submitTicket(createReq(1, 1, "t", "c"), 90001L);
        ticketService.submitTicket(createReq(1, 1, "t2", "c"), 90001L);
        ticketService.assignTicket(t1, 70001L, 70001L, "SUPPORT");

        TicketDataVO stats = ticketService.getTicketStatistics();
        assertThat(stats.pendingAssignCount()).isEqualTo(1);
        assertThat(stats.processingCount()).isEqualTo(1);
        assertThat(stats.todayCreatedCount()).isEqualTo(2);
    }

    @Test
    void shouldRejectRejectAfterCompleted() {
        String ticketId = ticketService.submitTicket(createReq(1, 1, "t", "c"), 90001L);
        ticketService.assignTicket(ticketId, 70001L, 70001L, "SUPPORT");
        ticketService.processTicket(ticketId, "RESOLVE", "已处理", 70001L);
        ticketService.confirmAndRate(ticketId, true, 5, "ok", 90001L);

        assertThatThrownBy(() -> ticketService.escalateTicket(ticketId, 2, "升", 90001L))
                .isInstanceOf(InvalidTicketRequestException.class);
    }

    private Ticket findByTicketId(String ticketId) {
        return ticketMapper.selectOne(new LambdaQueryWrapper<Ticket>().eq(Ticket::getTicketId, ticketId));
    }

    private List<TicketChat> ticketChats(String ticketId) {
        return ticketChatMapper.selectList(new LambdaQueryWrapper<TicketChat>()
                .eq(TicketChat::getTicketId, ticketId)
                .orderByAsc(TicketChat::getCreatedAt));
    }

    private TicketCreateReqDTO createReq(int type, int priority, String title, String content) {
        TicketCreateReqDTO req = new TicketCreateReqDTO();
        req.setTicketType(type);
        req.setPriority(priority);
        req.setTitle(title);
        req.setContent(content);
        return req;
    }
}
