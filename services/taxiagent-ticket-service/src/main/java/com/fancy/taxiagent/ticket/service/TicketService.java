package com.fancy.taxiagent.ticket.service;

import com.fancy.taxiagent.ticket.domain.dto.TicketCreateReqDTO;
import com.fancy.taxiagent.ticket.domain.dto.TicketQueryReqDTO;
import com.fancy.taxiagent.ticket.domain.vo.PageResult;
import com.fancy.taxiagent.ticket.domain.vo.TicketChatVO;
import com.fancy.taxiagent.ticket.domain.vo.TicketDataVO;
import com.fancy.taxiagent.ticket.domain.vo.TicketDetailVO;
import com.fancy.taxiagent.ticket.domain.vo.TicketSimpleVO;
import com.fancy.taxiagent.ticket.domain.vo.TicketVO;

import java.util.List;

public interface TicketService {

    // ============ C 端 ============

    String submitTicket(TicketCreateReqDTO req, Long userId);

    boolean cancelTicket(String ticketId, Long userId);

    boolean confirmAndRate(String ticketId, Boolean satisfied, Integer rating,
                           String feedbackContent, Long userId);

    PageResult<TicketVO> getUserTicketPage(Long userId, TicketQueryReqDTO req);

    TicketDetailVO getTicketDetail(String ticketId, Long operatorId, String operatorRole);

    List<TicketChatVO> getChatHistory(String ticketId, Long operatorId, String operatorRole);

    List<TicketSimpleVO> getUnfinishedTickets(Long userId, Integer limit);

    TicketDetailVO getLatestUnfinishedTicket(Long userId);

    boolean appendUserMessage(String ticketId, String content, Long userId);

    boolean escalateTicket(String ticketId, Integer targetLevel, String reason, Long userId);

    // ============ B 端 ============

    PageResult<TicketVO> getAdminTicketPage(TicketQueryReqDTO req);

    boolean assignTicket(String ticketId, Long targetHandlerId, Long operatorId, String operatorRole);

    boolean reassignTicket(String ticketId, Long targetHandlerId);

    boolean processTicket(String ticketId, String actionType, String content, Long operatorId);

    boolean escalateTicketByAdmin(String ticketId, Integer targetLevel, String reason, Long operatorId);

    TicketDataVO getTicketStatistics();
}
