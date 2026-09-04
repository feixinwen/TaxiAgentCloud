package com.fancy.taxiagent.ticket.controller;

import com.fancy.taxiagent.ticket.config.TicketSecurityConfiguration;
import com.fancy.taxiagent.ticket.config.TicketSecurityFailureHandler;
import com.fancy.taxiagent.ticket.domain.vo.TicketDataVO;
import com.fancy.taxiagent.ticket.domain.vo.TicketDetailVO;
import com.fancy.taxiagent.ticket.exception.InvalidTicketRequestException;
import com.fancy.taxiagent.ticket.exception.TicketNotFoundException;
import com.fancy.taxiagent.ticket.exception.TicketStateConflictException;
import com.fancy.taxiagent.ticket.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工单接口的鉴权、userId/role 提取与错误映射验证（真实 JWT 安全链）。
 */
@WebMvcTest(controllers = TicketController.class)
@Import({TicketExceptionHandler.class, TicketSecurityConfiguration.class, TicketSecurityFailureHandler.class})
class TicketControllerMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TicketService ticketService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode("user-token")).thenReturn(jwt("90001", "USER"));
        when(jwtDecoder.decode("admin-token")).thenReturn(jwt("50000", "ADMIN"));
        when(jwtDecoder.decode("support-token")).thenReturn(jwt("50001", "SUPPORT"));
    }

    @Test
    void shouldSubmitTicket() throws Exception {
        when(ticketService.submitTicket(any(), eq(90001L))).thenReturn("T20260814100001");

        mockMvc.perform(post("/api/tickets/submit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticketType":1,"priority":1,"title":"丢了手机","content":"黑色手机"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value("T20260814100001"));
    }

    @Test
    void shouldGetOwnDetail() throws Exception {
        when(ticketService.getTicketDetail("T1", 90001L, "USER")).thenReturn(detail());

        mockMvc.perform(get("/api/tickets/detail/T1").header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value("T1"))
                .andExpect(jsonPath("$.ticketStatus").value(0));
    }

    @Test
    void shouldReturn404ForMissingTicket() throws Exception {
        when(ticketService.getTicketDetail("T9", 90001L, "USER"))
                .thenThrow(new TicketNotFoundException("工单不存在"));

        mockMvc.perform(get("/api/tickets/detail/T9").header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
    }

    @Test
    void shouldReturn409ForStateConflict() throws Exception {
        when(ticketService.assignTicket(eq("T1"), eq(50001L), eq(50001L), eq("SUPPORT")))
                .thenThrow(new TicketStateConflictException("工单已被其他客服认领"));

        mockMvc.perform(post("/api/tickets/admin/assign/T1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer support-token"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_STATE_CONFLICT"));
    }

    @Test
    void shouldReturn400ForInvalidRequest() throws Exception {
        when(ticketService.submitTicket(any(), eq(90001L)))
                .thenThrow(new InvalidTicketRequestException("工单标题不能为空"));

        mockMvc.perform(post("/api/tickets/submit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void shouldRejectUserAccessingAdminEndpoint() throws Exception {
        mockMvc.perform(post("/api/tickets/admin/page")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectSupportReassign() throws Exception {
        mockMvc.perform(post("/api/tickets/admin/reassign/T1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer support-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldAllowAdminAssign() throws Exception {
        when(ticketService.assignTicket(eq("T1"), eq(50000L), eq(50000L), eq("ADMIN")))
                .thenReturn(true);

        mockMvc.perform(post("/api/tickets/admin/assign/T1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldAllowAdminStatistics() throws Exception {
        when(ticketService.getTicketStatistics())
                .thenReturn(new TicketDataVO(1L, 2L, 3L, 4L));

        mockMvc.perform(get("/api/tickets/admin/statistics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingAssignCount").value(1));
    }

    @Test
    void shouldReturn401WithoutToken() throws Exception {
        mockMvc.perform(get("/api/tickets/detail/T1")).andExpect(status().isUnauthorized());
    }

    @Test
    void shouldAllowAdminReadDetail() throws Exception {
        when(ticketService.getTicketDetail("T1", 50000L, "ADMIN")).thenReturn(detail());

        mockMvc.perform(get("/api/tickets/detail/T1").header(HttpHeaders.AUTHORIZATION, "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value("T1"));
    }

    private TicketDetailVO detail() {
        return new TicketDetailVO("1", "T1", "90001", 1, "乘客", null,
                1, "物品遗失", 1, "普通", 0, "待分配", null,
                "丢了手机", "黑色手机", null, LocalDateTime.now(), LocalDateTime.now(), List.of());
    }

    private Jwt jwt(String subject, String role) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("role", role)
                .claim("token_type", "access")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
    }
}
