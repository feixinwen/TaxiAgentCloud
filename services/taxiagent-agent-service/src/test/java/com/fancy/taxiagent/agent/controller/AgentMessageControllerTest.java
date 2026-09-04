package com.fancy.taxiagent.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.agent.config.AgentSecurityConfiguration;
import com.fancy.taxiagent.agent.config.AgentSecurityFailureHandler;
import com.fancy.taxiagent.agent.domain.dto.SendMessageRequest;
import com.fancy.taxiagent.agent.exception.AgentApiException;
import com.fancy.taxiagent.agent.filter.RequestTraceFilter;
import com.fancy.taxiagent.agent.service.AgentConversationQueryService;
import com.fancy.taxiagent.agent.service.AgentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用无需 Docker 的 MVC 切片验证消息 SSE 接口、安全边界和建立流前错误。
 */
@WebMvcTest(AgentController.class)
@Import({
        AgentSecurityConfiguration.class,
        AgentSecurityFailureHandler.class,
        AgentExceptionHandler.class,
        RequestTraceFilter.class
})
class AgentMessageControllerTest {

    private static final String ENDPOINT = "/api/agent/conversations/conversation-001/messages";
    private static final String RESUME_ENDPOINT = "/api/agent/conversations/conversation-001/resume";
    private static final UUID CLIENT_MESSAGE_ID =
            UUID.fromString("9be45a39-e497-41f4-9538-945c5667c9a1");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AgentService agentService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private AgentConversationQueryService agentConversationQueryService;

    private SseEmitter openEmitter;

    @AfterEach
    void closeEmitter() {
        if (openEmitter != null) {
            openEmitter.complete();
        }
    }

    @Test
    void shouldReturnEventStreamAndPassJwtSubjectAndHeaders() throws Exception {
        openEmitter = new SseEmitter(65_000L);
        openEmitter.send(SseEmitter.event()
                .name("run.started")
                .data(Map.of("runId", "run-001", "conversationId", "conversation-001")));
        SendMessageRequest expectedRequest = new SendMessageRequest(CLIENT_MESSAGE_ID, "取消订单会收费吗？");
        when(jwtDecoder.decode("user-token")).thenReturn(jwt("50001", "USER"));
        when(agentService.sendMessage(
                50001L,
                "Bearer user-token",
                "trace-message-001",
                "conversation-001",
                expectedRequest
        )).thenReturn(openEmitter);

        mockMvc.perform(post(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .header(RequestTraceFilter.TRACE_ID_HEADER, "trace-message-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(validRequestJson()))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(header().string(RequestTraceFilter.TRACE_ID_HEADER, "trace-message-001"));

        verify(agentService).sendMessage(
                50001L,
                "Bearer user-token",
                "trace-message-001",
                "conversation-001",
                expectedRequest
        );
    }

    @Test
    void shouldPassGeneratedTraceIdWhenHeaderIsMissing() throws Exception {
        openEmitter = new SseEmitter(65_000L);
        SendMessageRequest expectedRequest = new SendMessageRequest(CLIENT_MESSAGE_ID, "取消订单会收费吗？");
        when(jwtDecoder.decode("user-token")).thenReturn(jwt("50001", "USER"));
        when(agentService.sendMessage(
                eq(50001L),
                eq("Bearer user-token"),
                anyString(),
                eq("conversation-001"),
                eq(expectedRequest)
        )).thenReturn(openEmitter);

        MvcResult result = mockMvc.perform(post(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(validRequestJson()))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        String responseTraceId = result.getResponse().getHeader(RequestTraceFilter.TRACE_ID_HEADER);
        ArgumentCaptor<String> traceIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentService).sendMessage(
                eq(50001L),
                eq("Bearer user-token"),
                traceIdCaptor.capture(),
                eq("conversation-001"),
                eq(expectedRequest)
        );
        assertThat(responseTraceId).hasSize(32).matches("[A-Za-z0-9._-]+");
        assertThat(traceIdCaptor.getValue()).isEqualTo(responseTraceId);
    }

    @Test
    void shouldPassSanitizedTraceIdWhenHeaderIsInvalid() throws Exception {
        openEmitter = new SseEmitter(65_000L);
        SendMessageRequest expectedRequest = new SendMessageRequest(CLIENT_MESSAGE_ID, "取消订单会收费吗？");
        when(jwtDecoder.decode("user-token")).thenReturn(jwt("50001", "USER"));
        when(agentService.sendMessage(
                eq(50001L),
                eq("Bearer user-token"),
                anyString(),
                eq("conversation-001"),
                eq(expectedRequest)
        )).thenReturn(openEmitter);

        MvcResult result = mockMvc.perform(post(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .header(RequestTraceFilter.TRACE_ID_HEADER, "invalid trace id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(validRequestJson()))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        String responseTraceId = result.getResponse().getHeader(RequestTraceFilter.TRACE_ID_HEADER);
        ArgumentCaptor<String> traceIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(agentService).sendMessage(
                eq(50001L),
                eq("Bearer user-token"),
                traceIdCaptor.capture(),
                eq("conversation-001"),
                eq(expectedRequest)
        );
        assertThat(responseTraceId)
                .hasSize(32)
                .matches("[A-Za-z0-9._-]+")
                .isNotEqualTo("invalid trace id");
        assertThat(traceIdCaptor.getValue()).isEqualTo(responseTraceId);
    }

    @Test
    void shouldReturn401WithoutAuthentication() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(validRequestJson()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(agentService);
    }

    @Test
    void shouldReturn403ForWrongRole() throws Exception {
        when(jwtDecoder.decode("admin-token")).thenReturn(jwt("50000", "ADMIN"));

        mockMvc.perform(post(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(validRequestJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(agentService);
    }

    @Test
    void shouldReturnInvalidMessageBeforeInvokingService() throws Exception {
        when(jwtDecoder.decode("user-token")).thenReturn(jwt("50001", "USER"));
        String invalidRequest = objectMapper.writeValueAsString(
                new SendMessageRequest(CLIENT_MESSAGE_ID, "   "));

        mockMvc.perform(post(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(invalidRequest))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MESSAGE"));

        verify(agentService, never()).sendMessage(
                org.mockito.ArgumentMatchers.anyLong(),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void shouldReturnConversationNotFoundBeforeSseStarts() throws Exception {
        when(jwtDecoder.decode("user-token")).thenReturn(jwt("50001", "USER"));
        when(agentService.sendMessage(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any()))
                .thenThrow(new AgentApiException(
                        HttpStatus.NOT_FOUND,
                        "CONVERSATION_NOT_FOUND",
                        "对话不存在"
                ));

        mockMvc.perform(post(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(validRequestJson()))
                .andExpect(status().isNotFound())
                .andExpect(request().asyncNotStarted())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));
    }

    @Test
    void shouldReturnConversationBusyBeforeSseStarts() throws Exception {
        when(jwtDecoder.decode("user-token")).thenReturn(jwt("50001", "USER"));
        when(agentService.sendMessage(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any()))
                .thenThrow(new AgentApiException(
                        HttpStatus.CONFLICT,
                        "CONVERSATION_BUSY",
                        "对话正在处理中"
                ));

        mockMvc.perform(post(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(validRequestJson()))
                .andExpect(status().isConflict())
                .andExpect(request().asyncNotStarted())
                .andExpect(jsonPath("$.code").value("CONVERSATION_BUSY"));
    }

    @Test
    void shouldReturnDuplicateRunIdBeforeSseStarts() throws Exception {
        when(jwtDecoder.decode("user-token")).thenReturn(jwt("50001", "USER"));
        when(agentService.sendMessage(org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any()))
                .thenThrow(new AgentApiException(
                        HttpStatus.CONFLICT,
                        "DUPLICATE_MESSAGE",
                        "clientMessageId 已处理",
                        "existing-run-001"
                ));

        mockMvc.perform(post(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(validRequestJson()))
                .andExpect(status().isConflict())
                .andExpect(request().asyncNotStarted())
                .andExpect(jsonPath("$.code").value("DUPLICATE_MESSAGE"))
                .andExpect(jsonPath("$.runId").value("existing-run-001"));
    }

    @Test
    void shouldResumeWithJwtSubjectAuthorizationAndTraceId() throws Exception {
        openEmitter = new SseEmitter(65_000L);
        openEmitter.send(SseEmitter.event()
                .name("run.started")
                .data(Map.of("runId", "run-002", "conversationId", "conversation-001")));
        SendMessageRequest expectedRequest = new SendMessageRequest(CLIENT_MESSAGE_ID, "确认下单");
        when(jwtDecoder.decode("user-token")).thenReturn(jwt("50001", "USER"));
        when(agentService.resume(
                50001L,
                "Bearer user-token",
                "trace-resume-001",
                "conversation-001",
                expectedRequest
        )).thenReturn(openEmitter);

        mockMvc.perform(post(RESUME_ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .header(RequestTraceFilter.TRACE_ID_HEADER, "trace-resume-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(objectMapper.writeValueAsString(expectedRequest)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(header().string(RequestTraceFilter.TRACE_ID_HEADER, "trace-resume-001"));

        verify(agentService).resume(
                50001L,
                "Bearer user-token",
                "trace-resume-001",
                "conversation-001",
                expectedRequest
        );
    }

    @Test
    void shouldReturn401ForResumeWithoutAuthentication() throws Exception {
        mockMvc.perform(post(RESUME_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(objectMapper.writeValueAsString(
                                new SendMessageRequest(CLIENT_MESSAGE_ID, "确认下单"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(agentService);
    }

    private String validRequestJson() throws Exception {
        return objectMapper.writeValueAsString(
                new SendMessageRequest(CLIENT_MESSAGE_ID, "取消订单会收费吗？"));
    }

    private Jwt jwt(String subject, String role) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .audience(List.of("taxiagent-api"))
                .claim("role", role)
                .claim("token_type", "access")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build();
    }
}
