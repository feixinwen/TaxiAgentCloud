package com.fancy.taxiagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.agent.client.TicketServiceFeignClient;
import com.fancy.taxiagent.agent.client.dto.TicketSimpleView;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import feign.FeignException;
import feign.RetryableException;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 查询未完结工单列表工具：列出用户当前未完结的全部工单摘要。
 *
 * <p>空列表是正常状态，返回提示文本；其余 4xx 映射为 {@code TICKET_REQUEST_REJECTED}，
 * 5xx 与可重试失败映射为 {@code TICKET_UNAVAILABLE}。</p>
 */
@Component
public class QueryUnfinishedTicketsTool implements AgentTool {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TicketServiceFeignClient ticketServiceFeignClient;
    private final ObjectMapper objectMapper;

    public QueryUnfinishedTicketsTool(TicketServiceFeignClient ticketServiceFeignClient, ObjectMapper objectMapper) {
        this.ticketServiceFeignClient = ticketServiceFeignClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "queryUnfinishedTickets";
    }

    @Override
    public String description() {
        return "查询用户当前未完结工单列表。参数：limit（可选，返回条数）。";
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
        try {
            List<TicketSimpleView> tickets = ticketServiceFeignClient.listUnfinishedTickets(
                    context.authorization(), context.traceId(), arguments.limit());
            if (tickets == null || tickets.isEmpty()) {
                return "您当前没有未完结的工单。";
            }
            StringBuilder text = new StringBuilder("【未完结工单列表】共")
                    .append(tickets.size()).append("条\n\n");
            for (int index = 0; index < tickets.size(); index++) {
                TicketSimpleView ticket = tickets.get(index);
                text.append(index + 1).append(". 工单编号：").append(ticket.ticketId()).append("\n");
                text.append("   标题：").append(ticket.title()).append("\n");
                text.append("   类型：").append(ticket.ticketTypeDesc()).append("\n");
                text.append("   状态：").append(ticket.ticketStatusDesc()).append("\n");
                text.append("   创建时间：")
                        .append(ticket.createdAt() == null ? "" : ticket.createdAt().format(FORMATTER))
                        .append("\n");
                if (index < tickets.size() - 1) {
                    text.append("\n");
                }
            }
            return text.toString();
        } catch (RetryableException exception) {
            throw unavailable(exception);
        } catch (FeignException exception) {
            if (exception.status() >= 400 && exception.status() < 500) {
                throw new AgentExecutionException("TICKET_REQUEST_REJECTED", "工单请求被拒绝", exception);
            }
            throw unavailable(exception);
        } catch (Exception exception) {
            return "Tool execution failed: " + exception.getMessage();
        }
    }

    private AgentExecutionException unavailable(Throwable cause) {
        return new AgentExecutionException("TICKET_UNAVAILABLE", "工单服务暂不可用", cause);
    }

    /**
     * 查询未完结工单列表工具参数。
     *
     * @param limit 返回条数（可选）
     */
    public record Arguments(Integer limit) {
    }
}
