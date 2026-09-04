package com.fancy.taxiagent.agent.client;

import com.fancy.taxiagent.agent.client.dto.TicketAppendMessageRequest;
import com.fancy.taxiagent.agent.client.dto.TicketCreateRequest;
import com.fancy.taxiagent.agent.client.dto.TicketDetailView;
import com.fancy.taxiagent.agent.client.dto.TicketEscalateRequest;
import com.fancy.taxiagent.agent.client.dto.TicketSimpleView;
import com.fancy.taxiagent.agent.config.AgentFeignConfiguration;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Ticket Service 的 Feign 调用契约（C 端工单提交与查询）。
 */
@FeignClient(
        name = "taxiagent-ticket-service",
        path = "/api/tickets",
        configuration = AgentFeignConfiguration.class
)
public interface TicketServiceFeignClient {

    /**
     * 携带调用方显式提供的认证与链路标识提交工单。
     *
     * @param authorization 调用方提供的 Authorization 请求头
     * @param traceId 调用方提供的 X-Trace-Id 请求头
     * @param request 创建请求
     * @return 工单编号
     */
    @PostMapping("/submit")
    String submitTicket(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("X-Trace-Id") String traceId,
            @RequestBody TicketCreateRequest request
    );

    /**
     * 携带调用方显式提供的认证与链路标识查询工单详情（归属由 ticket-service 校验，非本人 404）。
     *
     * @param authorization 调用方提供的 Authorization 请求头
     * @param traceId 调用方提供的 X-Trace-Id 请求头
     * @param ticketId 工单编号
     * @return 工单详情
     */
    @GetMapping("/detail/{ticketId}")
    TicketDetailView getTicketDetail(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("X-Trace-Id") String traceId,
            @PathVariable("ticketId") String ticketId
    );

    /**
     * 携带调用方显式提供的认证与链路标识查询当前用户未完结工单列表。
     *
     * @param authorization 调用方提供的 Authorization 请求头
     * @param traceId 调用方提供的 X-Trace-Id 请求头
     * @param limit 可选返回条数
     * @return 未完结工单列表
     */
    @GetMapping("/my/unfinished")
    List<TicketSimpleView> listUnfinishedTickets(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("X-Trace-Id") String traceId,
            @RequestParam(value = "limit", required = false) Integer limit
    );

    /**
     * 携带调用方显式提供的认证与链路标识查询当前用户最近一个未完结工单；没有时上游 404。
     *
     * @param authorization 调用方提供的 Authorization 请求头
     * @param traceId 调用方提供的 X-Trace-Id 请求头
     * @return 最近未完结工单详情
     */
    @GetMapping("/my/latest-unfinished")
    TicketDetailView getLatestUnfinishedTicket(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("X-Trace-Id") String traceId
    );

    /**
     * 携带调用方显式提供的认证与链路标识向工单补充用户信息。
     *
     * @param authorization 调用方提供的 Authorization 请求头
     * @param traceId 调用方提供的 X-Trace-Id 请求头
     * @param request 补充请求
     */
    @PostMapping("/my/append")
    void appendTicketMessage(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("X-Trace-Id") String traceId,
            @RequestBody TicketAppendMessageRequest request
    );

    /**
     * 携带调用方显式提供的认证与链路标识升级工单优先级。
     *
     * @param authorization 调用方提供的 Authorization 请求头
     * @param traceId 调用方提供的 X-Trace-Id 请求头
     * @param request 升级请求
     */
    @PostMapping("/my/escalate")
    void escalateTicket(
            @RequestHeader("Authorization") String authorization,
            @RequestHeader("X-Trace-Id") String traceId,
            @RequestBody TicketEscalateRequest request
    );
}
