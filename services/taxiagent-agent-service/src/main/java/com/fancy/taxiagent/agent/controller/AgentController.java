package com.fancy.taxiagent.agent.controller;

import com.fancy.taxiagent.agent.domain.dto.CreateConversationResponse;
import com.fancy.taxiagent.agent.domain.dto.MessageResponse;
import com.fancy.taxiagent.agent.domain.dto.SendMessageRequest;
import com.fancy.taxiagent.agent.domain.dto.PageResult;
import com.fancy.taxiagent.agent.domain.dto.ConversationSummaryResponse;
import com.fancy.taxiagent.agent.exception.AgentApiException;
import com.fancy.taxiagent.agent.filter.RequestTraceFilter;
import com.fancy.taxiagent.agent.service.AgentService;
import com.fancy.taxiagent.agent.service.AgentConversationQueryService;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.validation.Valid;

/**
 * Agent 对话公开 API。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;
    private final AgentConversationQueryService queryService;

    public AgentController(AgentService agentService, AgentConversationQueryService queryService) {
        this.agentService = agentService;
        this.queryService = queryService;
    }

    /**
     * 使用 JWT subject 作为唯一用户身份创建活动对话。
     *
     * @param jwt 已验证的 Access Token
     * @return 新建对话
     */
    @PostMapping("/conversations")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<CreateConversationResponse> createConversation(@AuthenticationPrincipal Jwt jwt) {
        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException | NullPointerException exception) {
            throw invalidTokenSubject();
        }
        if (userId <= 0) {
            throw invalidTokenSubject();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(agentService.createConversation(userId));
    }

    /**
     * 分页列出当前用户的对话（最近活跃在前）。
     *
     * @param page 可选页码，缺省 1，合法区间 [1,50]
     * @param size 可选页大小，缺省 10，合法区间 [1,50]
     * @param jwt 已验证的 Access Token
     * @return 分页对话摘要
     */
    @GetMapping("/conversations")
    @PreAuthorize("hasRole('USER')")
    public PageResult<ConversationSummaryResponse> listConversations(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal Jwt jwt
    ) {
        int safePage = queryService.requireValidPage(page);
        int safeSize = queryService.requireValidSize(size, 10);
        return queryService.listConversations(userId(jwt), safePage, safeSize);
    }

    /**
     * 分页返回指定对话的历史消息（sequenceNo 升序）。
     *
     * @param conversationId 对外对话 ID
     * @param page 可选页码，缺省 1，合法区间 [1,50]
     * @param size 可选页大小，缺省 30，合法区间 [1,50]
     * @param jwt 已验证的 Access Token
     * @return 分页消息列表
     */
    @GetMapping("/conversations/{conversationId}/messages")
    @PreAuthorize("hasRole('USER')")
    public PageResult<MessageResponse> listMessages(
            @PathVariable String conversationId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal Jwt jwt
    ) {
        int safePage = queryService.requireValidPage(page);
        int safeSize = queryService.requireValidSize(size, 30);
        return queryService.findMessages(userId(jwt), conversationId, safePage, safeSize);
    }

    /**
     * 删除当前用户的对话（软删除：status → DELETED）。
     *
     * <p>删除后列表不再返回；历史/续发路径只认 ACTIVE 自然 404。
     * 重复删除幂等返回 204；非本人对话一律 404 不暴露存在性。</p>
     *
     * @param conversationId 对外对话 ID
     * @param jwt 已验证的 Access Token
     * @return 204 无响应体
     */
    @DeleteMapping("/conversations/{conversationId}")
    @PreAuthorize("hasRole('USER')")
    public ResponseEntity<Void> deleteConversation(
            @PathVariable String conversationId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        agentService.deleteConversation(userId(jwt), conversationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 发送用户消息并建立单次 Agent Run 的 SSE 流。
     *
     * @param conversationId 对外对话 ID
     * @param request 用户消息请求
     * @param authorization 原始 Authorization 请求头
     * @param jwt 已验证的 Access Token
     * @return 用于持续推送 Agent 事件的 SSE 发射器
     */
    @PostMapping(value = "/conversations/{conversationId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasRole('USER')")
    public SseEmitter sendMessage(
            @PathVariable String conversationId,
            @Valid @RequestBody SendMessageRequest request,
            @RequestHeader("Authorization") String authorization,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return agentService.sendMessage(
                userId(jwt),
                authorization,
                MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY),
                conversationId,
                request
        );
    }

    /**
     * 恢复 HITL 暂停的订单确认流程（SSE 流式响应）。
     *
     * @param conversationId 对外对话 ID
     * @param request 用户确认消息
     * @param authorization 原始 Authorization 请求头
     * @param jwt 已验证的 Access Token
     * @return 用于持续推送 Agent 事件的 SSE 发射器
     */
    @PostMapping(value = "/conversations/{conversationId}/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasRole('USER')")
    public SseEmitter resume(
            @PathVariable String conversationId,
            @Valid @RequestBody SendMessageRequest request,
            @RequestHeader("Authorization") String authorization,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return agentService.resume(
                userId(jwt),
                authorization,
                MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY),
                conversationId,
                request
        );
    }

    private AgentApiException invalidTokenSubject() {
        return new AgentApiException(
                HttpStatus.UNAUTHORIZED,
                "INVALID_TOKEN_SUBJECT",
                "Token 用户标识不合法"
        );
    }

    private long userId(Jwt jwt) {
        try {
            long userId = Long.parseLong(jwt.getSubject());
            if (userId <= 0) {
                throw invalidTokenSubject();
            }
            return userId;
        } catch (NumberFormatException | NullPointerException exception) {
            throw invalidTokenSubject();
        }
    }
}
