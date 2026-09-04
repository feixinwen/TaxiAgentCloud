package com.fancy.taxiagent.ticket.controller;

import com.fancy.taxiagent.ticket.domain.dto.TicketAppendMessageReqDTO;
import com.fancy.taxiagent.ticket.domain.dto.TicketCreateReqDTO;
import com.fancy.taxiagent.ticket.domain.dto.TicketEscalateReqDTO;
import com.fancy.taxiagent.ticket.domain.dto.TicketFeedbackReqDTO;
import com.fancy.taxiagent.ticket.domain.dto.TicketHandleReqDTO;
import com.fancy.taxiagent.ticket.domain.dto.TicketQueryReqDTO;
import com.fancy.taxiagent.ticket.domain.vo.PageResult;
import com.fancy.taxiagent.ticket.domain.vo.TicketChatVO;
import com.fancy.taxiagent.ticket.domain.vo.TicketDataVO;
import com.fancy.taxiagent.ticket.domain.vo.TicketDetailVO;
import com.fancy.taxiagent.ticket.domain.vo.TicketSimpleVO;
import com.fancy.taxiagent.ticket.domain.vo.TicketVO;
import com.fancy.taxiagent.ticket.service.TicketService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * 工单接口：C 端（乘客）+ B 端（客服/管理员）。
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    // ============ C 端 ============

    @PostMapping("/submit")
    @PreAuthorize("hasRole('USER')")
    public String submit(@RequestBody TicketCreateReqDTO req, Authentication authentication) {
        return ticketService.submitTicket(req, userId(authentication));
    }

    @PostMapping("/cancel/{ticketId}")
    @PreAuthorize("hasRole('USER')")
    public void cancel(@PathVariable String ticketId, Authentication authentication) {
        ticketService.cancelTicket(ticketId, userId(authentication));
    }

    @PostMapping("/feedback")
    @PreAuthorize("hasRole('USER')")
    public void feedback(@RequestBody TicketFeedbackReqDTO req, Authentication authentication) {
        ticketService.confirmAndRate(req.getTicketId(), req.getSatisfied(), req.getRating(),
                req.getFeedbackContent(), userId(authentication));
    }

    @PostMapping("/my/page")
    @PreAuthorize("hasRole('USER')")
    public PageResult<TicketVO> myPage(@RequestBody TicketQueryReqDTO req, Authentication authentication) {
        return ticketService.getUserTicketPage(userId(authentication), req);
    }

    @GetMapping("/detail/{ticketId}")
    @PreAuthorize("hasAnyRole('USER','ADMIN','SUPPORT')")
    public TicketDetailVO detail(@PathVariable String ticketId, Authentication authentication) {
        return ticketService.getTicketDetail(ticketId, userId(authentication), role(authentication));
    }

    @GetMapping("/{ticketId}/chat")
    @PreAuthorize("hasAnyRole('USER','ADMIN','SUPPORT')")
    public List<TicketChatVO> chatHistory(@PathVariable String ticketId, Authentication authentication) {
        return ticketService.getChatHistory(ticketId, userId(authentication), role(authentication));
    }

    @GetMapping("/my/unfinished")
    @PreAuthorize("hasRole('USER')")
    public List<TicketSimpleVO> unfinished(@RequestParam(required = false) Integer limit,
                                           Authentication authentication) {
        return ticketService.getUnfinishedTickets(userId(authentication), limit);
    }

    @GetMapping("/my/latest-unfinished")
    @PreAuthorize("hasRole('USER')")
    public TicketDetailVO latestUnfinished(Authentication authentication) {
        return ticketService.getLatestUnfinishedTicket(userId(authentication));
    }

    @PostMapping("/my/append")
    @PreAuthorize("hasRole('USER')")
    public void append(@RequestBody TicketAppendMessageReqDTO req, Authentication authentication) {
        ticketService.appendUserMessage(req.getTicketId(), req.getContent(), userId(authentication));
    }

    @PostMapping("/my/escalate")
    @PreAuthorize("hasRole('USER')")
    public void escalate(@RequestBody TicketEscalateReqDTO req, Authentication authentication) {
        ticketService.escalateTicket(req.getTicketId(), req.getTargetLevel(), req.getReason(),
                userId(authentication));
    }

    // ============ B 端 ============

    @PostMapping("/admin/page")
    @PreAuthorize("hasAnyRole('ADMIN','SUPPORT')")
    public PageResult<TicketVO> adminPage(@RequestBody TicketQueryReqDTO req) {
        return ticketService.getAdminTicketPage(req);
    }

    @PostMapping("/admin/assign/{ticketId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPPORT')")
    public void assign(@PathVariable String ticketId,
                       @RequestParam(required = false) Long handlerId,
                       Authentication authentication) {
        Long currentUserId = userId(authentication);
        ticketService.assignTicket(ticketId, handlerId != null ? handlerId : currentUserId,
                currentUserId, role(authentication));
    }

    @PostMapping("/admin/reassign/{ticketId}")
    @PreAuthorize("hasRole('ADMIN')")
    public void reassign(@PathVariable String ticketId,
                         @RequestParam(required = false) Long handlerId,
                         Authentication authentication) {
        Long currentUserId = userId(authentication);
        ticketService.reassignTicket(ticketId, handlerId != null ? handlerId : currentUserId);
    }

    @PostMapping("/admin/process")
    @PreAuthorize("hasAnyRole('ADMIN','SUPPORT')")
    public void process(@RequestBody TicketHandleReqDTO req, Authentication authentication) {
        ticketService.processTicket(req.getTicketId(), req.getActionType(), req.getContent(),
                userId(authentication));
    }

    @PostMapping("/admin/reply")
    @PreAuthorize("hasAnyRole('ADMIN','SUPPORT')")
    public void reply(@RequestBody TicketHandleReqDTO req, Authentication authentication) {
        ticketService.processTicket(req.getTicketId(), "REPLY", req.getContent(), userId(authentication));
    }

    @GetMapping("/admin/statistics")
    @PreAuthorize("hasRole('ADMIN')")
    public TicketDataVO statistics() {
        return ticketService.getTicketStatistics();
    }

    @PostMapping("/admin/escalate")
    @PreAuthorize("hasAnyRole('ADMIN','SUPPORT')")
    public void escalateAdmin(@RequestBody TicketEscalateReqDTO req, Authentication authentication) {
        ticketService.escalateTicketByAdmin(req.getTicketId(), req.getTargetLevel(), req.getReason(),
                userId(authentication));
    }

    private Long userId(Authentication authentication) {
        return Long.parseLong(authentication.getName());
    }

    private String role(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .findFirst()
                .map(authority -> authority.substring("ROLE_".length()).toUpperCase(Locale.ROOT))
                .orElse("USER");
    }
}
