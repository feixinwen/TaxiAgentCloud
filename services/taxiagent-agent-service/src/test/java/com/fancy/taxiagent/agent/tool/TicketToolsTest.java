package com.fancy.taxiagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.agent.client.TicketServiceFeignClient;
import com.fancy.taxiagent.agent.client.dto.TicketChatView;
import com.fancy.taxiagent.agent.client.dto.TicketCreateRequest;
import com.fancy.taxiagent.agent.client.dto.TicketDetailView;
import com.fancy.taxiagent.agent.client.dto.TicketSimpleView;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 5 个工单工具的参数校验、上游错误映射与文本渲染。
 */
class TicketToolsTest {

    private static final String AUTH = "Bearer t";
    private static final String TRACE = "trace-1";

    private final TicketServiceFeignClient client = mock(TicketServiceFeignClient.class);
    private final AgentStreamSink sink = mock(AgentStreamSink.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final CreateTicketTool createTool = new CreateTicketTool(client, objectMapper);
    private final QueryTicketProgressTool progressTool = new QueryTicketProgressTool(client, objectMapper);
    private final QueryUnfinishedTicketsTool unfinishedTool = new QueryUnfinishedTicketsTool(client, objectMapper);
    private final EscalateTicketTool escalateTool = new EscalateTicketTool(client, objectMapper);
    private final AppendTicketMessageTool appendTool = new AppendTicketMessageTool(client, objectMapper);

    // ===== createTicket =====

    @Test
    void shouldCreateTicketWithDefaultPriorityAndReturnId() {
        when(client.submitTicket(eq(AUTH), eq(TRACE), any(TicketCreateRequest.class)))
                .thenReturn("FT20260830001");

        String result = createTool.execute(
                "{\"ticketType\":3,\"title\":\"服务投诉\",\"content\":\"司机中途甩客\"}", context(), sink);

        assertThat(result).isEqualTo("工单已创建，工单编号：FT20260830001");
        ArgumentCaptor<TicketCreateRequest> captor = ArgumentCaptor.forClass(TicketCreateRequest.class);
        verify(client).submitTicket(eq(AUTH),
                eq(TRACE), captor.capture());
        assertThat(captor.getValue().userType()).isEqualTo(1);
        assertThat(captor.getValue().priority()).isEqualTo(1);
        assertThat(captor.getValue().orderId()).isNull();
        assertThat(captor.getValue().ticketType()).isEqualTo(3);
        assertThat(captor.getValue().title()).isEqualTo("服务投诉");
    }

    @Test
    void shouldCreateTicketWithOptionalOrderIdAndPriority() {
        when(client.submitTicket(eq(AUTH), eq(TRACE), any(TicketCreateRequest.class)))
                .thenReturn("FT2");

        createTool.execute(
                "{\"ticketType\":1,\"title\":\"物品遗失\",\"content\":\"手机落车上\",\"orderId\":\"12345\",\"priority\":2}",
                context(), sink);

        ArgumentCaptor<TicketCreateRequest> captor = ArgumentCaptor.forClass(TicketCreateRequest.class);
        verify(client).submitTicket(eq(AUTH),
                eq(TRACE), captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo(12345L);
        assertThat(captor.getValue().priority()).isEqualTo(2);
    }

    @Test
    void shouldRejectMissingTitle() {
        String result = createTool.execute("{\"ticketType\":3,\"content\":\"内容\"}", context(), sink);
        assertThat(result).startsWith("Tool execution failed:").contains("标题");
    }

    @Test
    void shouldRejectInvalidTicketType() {
        String result = createTool.execute(
                "{\"ticketType\":9,\"title\":\"标题\",\"content\":\"内容\"}", context(), sink);
        assertThat(result).startsWith("Tool execution failed:").contains("工单类型");
    }

    @Test
    void shouldRejectNonNumericOrderId() {
        String result = createTool.execute(
                "{\"ticketType\":5,\"title\":\"标题\",\"content\":\"内容\",\"orderId\":\"abc\"}", context(), sink);
        assertThat(result).startsWith("Tool execution failed:").contains("订单号");
    }

    // ===== queryTicketProgress =====

    @Test
    void shouldRenderTicketDetailWithRecentChatOnly() {
        LocalDateTime time = LocalDateTime.of(2026, 8, 30, 10, 0);
        List<TicketChatView> chats = new java.util.ArrayList<>();
        for (int index = 1; index <= 6; index++) {
            chats.add(new TicketChatView(index % 2 == 0 ? "客服" : "乘客", "消息" + index, time.plusMinutes(index)));
        }
        when(client.getTicketDetail(AUTH, TRACE, "FT1")).thenReturn(new TicketDetailView(
                "FT1", "物品遗失", "手机落车上", null, "物品遗失", "普通", "处理中",
                null, time, time, chats));

        String result = progressTool.execute("{\"ticketId\":\"FT1\"}", context(), sink);

        assertThat(result)
                .contains("【工单信息】", "工单编号：FT1", "当前状态：处理中")
                .contains("【沟通记录】", "消息2", "消息6")
                .doesNotContain("消息1");
    }

    @Test
    void shouldQueryLatestUnfinishedWhenTicketIdBlank() {
        when(client.getLatestUnfinishedTicket(AUTH, TRACE)).thenReturn(new TicketDetailView(
                "FT9", "费用争议", "多扣费", null, "费用争议", "普通", "待认领",
                null, LocalDateTime.now(), LocalDateTime.now(), List.of()));

        String result = progressTool.execute("{\"ticketId\":\"\"}", context(), sink);

        assertThat(result).contains("工单编号：FT9", "待认领");
    }

    @Test
    void shouldReturnNoUnfinishedTextWhenLatestIs404() {
        when(client.getLatestUnfinishedTicket(AUTH, TRACE)).thenThrow(
                FeignException.errorStatus("getLatestUnfinishedTicket", response(404)));

        String result = progressTool.execute("{}", context(), sink);

        assertThat(result).isEqualTo("您当前没有未完结的工单。");
    }

    @Test
    void shouldReturnTicketMissingTextWhenDetailIs404() {
        when(client.getTicketDetail(AUTH, TRACE, "FT404")).thenThrow(
                FeignException.errorStatus("getTicketDetail", response(404)));

        String result = progressTool.execute("{\"ticketId\":\"FT404\"}", context(), sink);

        assertThat(result).contains("工单不存在");
    }

    // ===== queryUnfinishedTickets =====

    @Test
    void shouldRenderUnfinishedTicketList() {
        when(client.listUnfinishedTickets(AUTH, TRACE, 5)).thenReturn(List.of(
                new TicketSimpleView("FT1", "物品遗失", "物品遗失", "处理中", LocalDateTime.of(2026, 8, 30, 9, 0)),
                new TicketSimpleView("FT2", "服务投诉", "服务投诉", "待认领", LocalDateTime.of(2026, 8, 30, 8, 0))));

        String result = unfinishedTool.execute("{\"limit\":5}", context(), sink);

        assertThat(result)
                .contains("【未完结工单列表】共2条", "1. 工单编号：FT1", "2. 工单编号：FT2", "处理中", "待认领");
    }

    @Test
    void shouldReturnEmptyTextWhenNoUnfinishedTickets() {
        when(client.listUnfinishedTickets(AUTH, TRACE, null)).thenReturn(List.of());

        String result = unfinishedTool.execute("{}", context(), sink);

        assertThat(result).isEqualTo("您当前没有未完结的工单。");
    }

    // ===== escalateTicket =====

    @Test
    void shouldRejectInvalidTargetLevel() {
        String result = escalateTool.execute(
                "{\"ticketId\":\"FT1\",\"reason\":\"用户很愤怒\",\"targetLevel\":4}", context(), sink);
        assertThat(result).startsWith("Tool execution failed:").contains("目标级别");
    }

    @Test
    void shouldEscalateToUrgentAndConfirm() {
        String result = escalateTool.execute(
                "{\"ticketId\":\"FT1\",\"reason\":\" 提及人身安全 \",\"targetLevel\":3}", context(), sink);

        assertThat(result).contains("特急", "优先处理");
        ArgumentCaptor<com.fancy.taxiagent.agent.client.dto.TicketEscalateRequest> captor =
                ArgumentCaptor.forClass(com.fancy.taxiagent.agent.client.dto.TicketEscalateRequest.class);
        verify(client).escalateTicket(eq(AUTH),
                eq(TRACE), captor.capture());
        assertThat(captor.getValue().ticketId()).isEqualTo("FT1");
        assertThat(captor.getValue().targetLevel()).isEqualTo(3);
        assertThat(captor.getValue().reason()).isEqualTo("提及人身安全");
    }

    @Test
    void shouldReturnTicketMissingTextWhenEscalateIs404() {
        doThrow(FeignException.errorStatus("escalateTicket", response(404)))
                .when(client).escalateTicket(eq(AUTH), eq(TRACE), any());

        String result = escalateTool.execute(
                "{\"ticketId\":\"FT404\",\"reason\":\"升级\",\"targetLevel\":2}", context(), sink);

        assertThat(result).contains("工单不存在");
    }

    // ===== appendTicketMessage =====

    @Test
    void shouldAppendMessageAndConfirm() {
        String result = appendTool.execute(
                "{\"ticketId\":\"FT1\",\"content\":\" 补充：车牌尾号888 \"}", context(), sink);

        assertThat(result).contains("补充信息已提交");
        ArgumentCaptor<com.fancy.taxiagent.agent.client.dto.TicketAppendMessageRequest> captor =
                ArgumentCaptor.forClass(com.fancy.taxiagent.agent.client.dto.TicketAppendMessageRequest.class);
        verify(client).appendTicketMessage(eq(AUTH),
                eq(TRACE), captor.capture());
        assertThat(captor.getValue().ticketId()).isEqualTo("FT1");
        assertThat(captor.getValue().content()).isEqualTo("补充：车牌尾号888");
    }

    @Test
    void shouldRejectMissingAppendContent() {
        String result = appendTool.execute("{\"ticketId\":\"FT1\",\"content\":\"\"}", context(), sink);
        assertThat(result).startsWith("Tool execution failed:").contains("补充");
    }

    // ===== 错误映射 =====

    @Test
    void shouldMapClientErrorToTicketRequestRejected() {
        when(client.submitTicket(eq(AUTH), eq(TRACE), any(TicketCreateRequest.class)))
                .thenThrow(FeignException.errorStatus("submitTicket", response(400)));

        assertThatThrownBy(() -> createTool.execute(
                "{\"ticketType\":3,\"title\":\"标题\",\"content\":\"内容\"}", context(), sink))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("TICKET_REQUEST_REJECTED");
    }

    @Test
    void shouldMapServerErrorToTicketUnavailable() {
        when(client.submitTicket(eq(AUTH), eq(TRACE), any(TicketCreateRequest.class)))
                .thenThrow(FeignException.errorStatus("submitTicket", response(503)));

        assertThatThrownBy(() -> createTool.execute(
                "{\"ticketType\":3,\"title\":\"标题\",\"content\":\"内容\"}", context(), sink))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("TICKET_UNAVAILABLE");
    }

    @Test
    void shouldMapRetryableFailureToTicketUnavailable() {
        when(client.listUnfinishedTickets(eq(AUTH), eq(TRACE), any())).thenThrow(new RetryableException(
                0, "connect failed", Request.HttpMethod.GET, new ConnectException("refused"),
                (Long) null, request()));

        assertThatThrownBy(() -> unfinishedTool.execute("{}", context(), sink))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(error -> ((AgentExecutionException) error).getCode())
                .isEqualTo("TICKET_UNAVAILABLE");
    }

    private AgentExecutionContext context() {
        return new AgentExecutionContext(
                50001L, "conv-1", "run-1", AUTH, TRACE, "我要投诉", List.of());
    }

    private Request request() {
        return Request.create(Request.HttpMethod.POST, "http://taxiagent-ticket-service/api/tickets/submit",
                Map.of(), new byte[0], StandardCharsets.UTF_8);
    }

    private Response response(int status) {
        return Response.builder()
                .status(status)
                .reason("failure")
                .request(request())
                .body("", StandardCharsets.UTF_8)
                .build();
    }
}
