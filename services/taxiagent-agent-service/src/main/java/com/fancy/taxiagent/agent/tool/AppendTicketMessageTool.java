package com.fancy.taxiagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.agent.client.TicketServiceFeignClient;
import com.fancy.taxiagent.agent.client.dto.TicketAppendMessageRequest;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import feign.FeignException;
import feign.RetryableException;
import org.springframework.stereotype.Component;

/**
 * 工单补充信息工具：向指定工单追加用户补充内容（细节描述、凭证说明等）。
 *
 * <p>404（工单不存在）返回提示文本；其余 4xx 映射为 {@code TICKET_REQUEST_REJECTED}，
 * 5xx 与可重试失败映射为 {@code TICKET_UNAVAILABLE}。</p>
 */
@Component
public class AppendTicketMessageTool implements AgentTool {

    private static final String TICKET_MISSING = "工单不存在，请确认工单编号后重试。";

    private final TicketServiceFeignClient ticketServiceFeignClient;
    private final ObjectMapper objectMapper;

    public AppendTicketMessageTool(TicketServiceFeignClient ticketServiceFeignClient, ObjectMapper objectMapper) {
        this.ticketServiceFeignClient = ticketServiceFeignClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "appendTicketMessage";
    }

    @Override
    public String description() {
        return "向指定工单补充用户信息（补充细节、说明凭证等）。参数：ticketId（必填）、content（必填，补充内容）。";
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
        if (arguments.ticketId() == null || arguments.ticketId().isBlank()) {
            return "Tool execution failed: 请提供工单编号";
        }
        if (arguments.content() == null || arguments.content().isBlank()) {
            return "Tool execution failed: 请提供需要补充的内容";
        }
        try {
            ticketServiceFeignClient.appendTicketMessage(
                    context.authorization(),
                    context.traceId(),
                    new TicketAppendMessageRequest(arguments.ticketId().trim(), arguments.content().trim()));
            return "您的补充信息已提交，客服会尽快查看并处理。";
        } catch (RetryableException exception) {
            throw unavailable(exception);
        } catch (FeignException exception) {
            if (exception.status() == 404) {
                return TICKET_MISSING;
            }
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
     * 工单补充信息工具参数。
     *
     * @param ticketId 工单编号（必填）
     * @param content 补充内容（必填）
     */
    public record Arguments(
            String ticketId,
            String content
    ) {
    }
}
