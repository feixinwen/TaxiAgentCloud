package com.fancy.taxiagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.agent.client.TicketServiceFeignClient;
import com.fancy.taxiagent.agent.client.dto.TicketEscalateRequest;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import feign.FeignException;
import feign.RetryableException;
import org.springframework.stereotype.Component;

/**
 * 升级工单工具：将工单优先级升级为紧急（2）或特急（3）。
 *
 * <p>用于用户情绪激动、提及人身安全或报警等需要客服优先处理的场景。
 * 404（工单不存在）返回提示文本；其余 4xx 映射为 {@code TICKET_REQUEST_REJECTED}，
 * 5xx 与可重试失败映射为 {@code TICKET_UNAVAILABLE}。</p>
 */
@Component
public class EscalateTicketTool implements AgentTool {

    private static final String TICKET_MISSING = "工单不存在，请确认工单编号后重试。";

    private final TicketServiceFeignClient ticketServiceFeignClient;
    private final ObjectMapper objectMapper;

    public EscalateTicketTool(TicketServiceFeignClient ticketServiceFeignClient, ObjectMapper objectMapper) {
        this.ticketServiceFeignClient = ticketServiceFeignClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "escalateTicket";
    }

    @Override
    public String description() {
        return "将工单升级为更高优先级（用户情绪激动、提及人身安全或报警等场景使用）。"
                + "参数：ticketId（必填）、reason（必填，升级原因）、targetLevel（必填，2 紧急/3 特急）。";
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
        if (arguments.reason() == null || arguments.reason().isBlank()) {
            return "Tool execution failed: 请提供升级原因";
        }
        if (arguments.targetLevel() == null
                || (arguments.targetLevel() != 2 && arguments.targetLevel() != 3)) {
            return "Tool execution failed: 目标级别无效，仅支持 2（紧急）或 3（特急）";
        }
        try {
            ticketServiceFeignClient.escalateTicket(
                    context.authorization(),
                    context.traceId(),
                    new TicketEscalateRequest(arguments.ticketId().trim(), arguments.targetLevel(),
                            arguments.reason().trim()));
            String levelDesc = arguments.targetLevel() == 2 ? "紧急" : "特急";
            return "工单已成功升级为【" + levelDesc + "】级别，客服将优先处理您的问题。";
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
     * 升级工单工具参数。
     *
     * @param ticketId 工单编号（必填）
     * @param reason 升级原因（必填）
     * @param targetLevel 目标级别 2 紧急/3 特急（必填）
     */
    public record Arguments(
            String ticketId,
            String reason,
            Integer targetLevel
    ) {
    }
}
