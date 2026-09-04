package com.fancy.taxiagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.agent.client.TicketServiceFeignClient;
import com.fancy.taxiagent.agent.client.dto.TicketCreateRequest;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import feign.FeignException;
import feign.RetryableException;
import org.springframework.stereotype.Component;

/**
 * 创建工单工具：为用户提交客服工单（物品遗失/费用争议/服务投诉/安全问题/其他）。
 *
 * <p>标题、内容、类型必填；优先级缺省为普通（1），关联订单号可选。userType 固定
 * 1（乘客）。上游 4xx 拒绝映射为 {@code TICKET_REQUEST_REJECTED}，5xx 与可重试失败
 * 映射为 {@code TICKET_UNAVAILABLE}；参数校验失败返回 "Tool execution failed: "
 * 开头的拒绝文本，模型可修正后重试。</p>
 */
@Component
public class CreateTicketTool implements AgentTool {

    private final TicketServiceFeignClient ticketServiceFeignClient;
    private final ObjectMapper objectMapper;

    public CreateTicketTool(TicketServiceFeignClient ticketServiceFeignClient, ObjectMapper objectMapper) {
        this.ticketServiceFeignClient = ticketServiceFeignClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "createTicket";
    }

    @Override
    public String description() {
        return "为用户创建客服工单。参数：ticketType（必填，1 物品遗失/2 费用争议/3 服务投诉/4 安全问题/5 其他）、"
                + "title（必填，工单标题）、content（必填，问题描述，尽量包含订单号/时间/地点等细节）、"
                + "priority（可选，1 普通/2 紧急/3 特急，缺省普通）、orderId（可选，关联订单号）。";
    }

    @Override
    public Class<?> inputType() {
        return Arguments.class;
    }

    @Override
    public String execute(String jsonArgs, AgentExecutionContext context, AgentStreamSink sink) {
        try {
            Arguments arguments = objectMapper.readValue(jsonArgs, Arguments.class);
            if (arguments.title() == null || arguments.title().isBlank()) {
                return "Tool execution failed: 请提供工单标题";
            }
            if (arguments.content() == null || arguments.content().isBlank()) {
                return "Tool execution failed: 请提供问题描述";
            }
            if (arguments.ticketType() == null || arguments.ticketType() < 1 || arguments.ticketType() > 5) {
                return "Tool execution failed: 工单类型必须是 1（物品遗失）/2（费用争议）/3（服务投诉）/4（安全问题）/5（其他）";
            }
            if (arguments.priority() != null && (arguments.priority() < 1 || arguments.priority() > 3)) {
                return "Tool execution failed: 优先级必须是 1（普通）/2（紧急）/3（特急）";
            }
            Long orderId = parseOrderId(arguments.orderId());
            if (orderId == null && arguments.orderId() != null && !arguments.orderId().isBlank()) {
                return "Tool execution failed: 订单号必须是数字";
            }
            String ticketId = ticketServiceFeignClient.submitTicket(
                    context.authorization(),
                    context.traceId(),
                    new TicketCreateRequest(1, orderId, arguments.ticketType(),
                            arguments.priority() == null ? 1 : arguments.priority(),
                            arguments.title().trim(), arguments.content().trim()));
            return "工单已创建，工单编号：" + ticketId;
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

    private Long parseOrderId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private AgentExecutionException unavailable(Throwable cause) {
        return new AgentExecutionException("TICKET_UNAVAILABLE", "工单服务暂不可用", cause);
    }

    /**
     * 创建工单工具参数。
     *
     * @param orderId 关联订单号（可选）
     * @param title 工单标题（必填）
     * @param content 问题描述（必填）
     * @param priority 优先级 1 普通/2 紧急/3 特急（可选，缺省普通）
     * @param ticketType 工单类型 1 物品遗失/2 费用争议/3 服务投诉/4 安全问题/5 其他（必填）
     */
    public record Arguments(
            String orderId,
            String title,
            String content,
            Integer priority,
            Integer ticketType
    ) {
    }
}
