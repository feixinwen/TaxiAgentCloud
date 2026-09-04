package com.fancy.taxiagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.agent.client.TicketServiceFeignClient;
import com.fancy.taxiagent.agent.client.dto.TicketChatView;
import com.fancy.taxiagent.agent.client.dto.TicketDetailView;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import feign.FeignException;
import feign.RetryableException;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 查询工单进度工具：查询指定工单详情，未指定编号时查询用户最近一个未完结工单。
 *
 * <p>404（工单不存在 / 无未完结工单）是正常状态，返回提示文本而非异常；其余 4xx 映射为
 * {@code TICKET_REQUEST_REJECTED}，5xx 与可重试失败映射为 {@code TICKET_UNAVAILABLE}。
 * 沟通记录只向模型回传最近 5 条，避免长对话撑大上下文。</p>
 */
@Component
public class QueryTicketProgressTool implements AgentTool {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_CHAT_MESSAGES = 5;

    private static final String NO_UNFINISHED = "您当前没有未完结的工单。";
    private static final String TICKET_MISSING = "工单不存在，请确认工单编号后重试。";

    private final TicketServiceFeignClient ticketServiceFeignClient;
    private final ObjectMapper objectMapper;

    public QueryTicketProgressTool(TicketServiceFeignClient ticketServiceFeignClient, ObjectMapper objectMapper) {
        this.ticketServiceFeignClient = ticketServiceFeignClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "queryTicketProgress";
    }

    @Override
    public String description() {
        return "查询工单进度。参数：ticketId（可选）。未提供时查询用户最近一个未完结工单；"
                + "提供时查询指定工单（含最近沟通记录）。";
    }

    @Override
    public Class<?> inputType() {
        return Arguments.class;
    }

    @Override
    public String execute(String jsonArgs, AgentExecutionContext context, AgentStreamSink sink) {
        final Arguments arguments;
        try {
            arguments = objectMapper.readValue(jsonArgs, Arguments.class);
        } catch (Exception exception) {
            return "Tool execution failed: " + exception.getMessage();
        }
        boolean byId = arguments.ticketId() != null && !arguments.ticketId().isBlank();
        try {
            TicketDetailView detail = byId
                    ? ticketServiceFeignClient.getTicketDetail(
                            context.authorization(), context.traceId(), arguments.ticketId().trim())
                    : ticketServiceFeignClient.getLatestUnfinishedTicket(
                            context.authorization(), context.traceId());
            if (detail == null) {
                return byId ? TICKET_MISSING : NO_UNFINISHED;
            }
            return render(detail);
        } catch (RetryableException exception) {
            throw unavailable(exception);
        } catch (FeignException exception) {
            if (exception.status() == 404) {
                return byId ? TICKET_MISSING : NO_UNFINISHED;
            }
            if (exception.status() >= 400 && exception.status() < 500) {
                throw new AgentExecutionException("TICKET_REQUEST_REJECTED", "工单请求被拒绝", exception);
            }
            throw unavailable(exception);
        } catch (Exception exception) {
            return "Tool execution failed: " + exception.getMessage();
        }
    }

    /**
     * 渲染工单详情文本（工单信息 + 最近沟通记录）。
     *
     * @param detail 工单详情
     * @return 模型可见文本
     */
    private String render(TicketDetailView detail) {
        StringBuilder text = new StringBuilder();
        text.append("【工单信息】\n");
        text.append("工单编号：").append(detail.ticketId()).append("\n");
        text.append("工单标题：").append(detail.title()).append("\n");
        text.append("工单类型：").append(detail.ticketTypeDesc()).append("\n");
        text.append("优先级：").append(detail.priorityDesc()).append("\n");
        text.append("当前状态：").append(detail.ticketStatusDesc()).append("\n");
        if (detail.createdAt() != null) {
            text.append("创建时间：").append(detail.createdAt().format(FORMATTER)).append("\n");
        }
        if (detail.updatedAt() != null) {
            text.append("更新时间：").append(detail.updatedAt().format(FORMATTER)).append("\n");
        }
        text.append("工单内容：").append(detail.content()).append("\n");
        if (detail.processResult() != null && !detail.processResult().isBlank()) {
            text.append("处理结果：").append(detail.processResult()).append("\n");
        }
        List<TicketChatView> chatHistory = detail.chatHistory();
        if (chatHistory != null && !chatHistory.isEmpty()) {
            text.append("\n【沟通记录】\n");
            int start = Math.max(0, chatHistory.size() - MAX_CHAT_MESSAGES);
            for (int index = start; index < chatHistory.size(); index++) {
                TicketChatView chat = chatHistory.get(index);
                text.append("[").append(chat.createdAt() == null ? "" : chat.createdAt().format(FORMATTER))
                        .append("] ").append(chat.senderRoleDesc()).append("：")
                        .append(chat.content()).append("\n");
            }
        }
        return text.toString();
    }

    private AgentExecutionException unavailable(Throwable cause) {
        return new AgentExecutionException("TICKET_UNAVAILABLE", "工单服务暂不可用", cause);
    }

    /**
     * 查询工单进度工具参数。
     *
     * @param ticketId 工单编号（可选，未提供时查最近未完结工单）
     */
    public record Arguments(String ticketId) {
    }
}
