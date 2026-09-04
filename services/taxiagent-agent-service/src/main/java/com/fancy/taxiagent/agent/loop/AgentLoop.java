package com.fancy.taxiagent.agent.loop;

import com.fancy.taxiagent.agent.config.AgentExecutionProperties;
import com.fancy.taxiagent.agent.config.AgentModelProperties;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.domain.model.SupportAgentResult;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.model.AgentModelGateway;
import com.fancy.taxiagent.agent.model.ModelRequest;
import com.fancy.taxiagent.agent.model.ModelToolCall;
import com.fancy.taxiagent.agent.model.ModelToolResult;
import com.fancy.taxiagent.agent.model.ModelTurn;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import com.fancy.taxiagent.agent.tool.AgentTool;
import com.fancy.taxiagent.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 通用 Agent 执行循环：流式模型调用 + 工具白名单检查 + 显式工具执行 + 截止时间与轮数控制。
 *
 * <p>各 Agent 差异仅在系统提示词与允许的工具集合。</p>
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final AgentModelGateway modelGateway;
    private final AgentExecutionProperties executionProperties;
    private final AgentModelProperties modelProperties;
    private final ToolRegistry toolRegistry;
    private final LongSupplier nanoTime;

    @Autowired
    public AgentLoop(AgentModelGateway modelGateway,
                     AgentExecutionProperties executionProperties,
                     AgentModelProperties modelProperties,
                     ToolRegistry toolRegistry) {
        this(modelGateway, executionProperties, modelProperties, toolRegistry, System::nanoTime);
    }

    /**
     * 以可替换时钟创建 Agent 执行循环（测试用）。
     *
     * @param modelGateway 模型网关
     * @param executionProperties 执行边界配置
     * @param modelProperties 模型与提示词配置
     * @param toolRegistry 工具注册表
     * @param nanoTime 纳秒时钟源
     */
    public AgentLoop(AgentModelGateway modelGateway,
                     AgentExecutionProperties executionProperties,
                     AgentModelProperties modelProperties,
                     ToolRegistry toolRegistry,
                     LongSupplier nanoTime) {
        this.modelGateway = modelGateway;
        this.executionProperties = executionProperties;
        this.modelProperties = modelProperties;
        this.toolRegistry = toolRegistry;
        this.nanoTime = nanoTime;
    }

    /**
     * 执行一次 Agent Run。
     *
     * @param context 显式执行上下文
     * @param sink 流事件出口
     * @param systemPrompt 该 Agent 的系统提示词
     * @param agentType 该 Agent 类型标签（日志用）
     * @param allowedTools 允许模型调用的工具名集合
     * @return 聚合执行结果
     */
    public SupportAgentResult execute(AgentExecutionContext context, AgentStreamSink sink,
                                      String systemPrompt, String agentType, Set<String> allowedTools) {
        long startedAt = nanoTime.getAsLong();
        List<ModelTurn> turns = new ArrayList<>(context.history());
        turns.add(ModelTurn.user(context.userMessage()));
        StringBuilder answer = new StringBuilder();
        int toolCallCount = 0;

        for (int round = 1; round <= executionProperties.getMaxModelRounds(); round++) {
            ensureWithinDeadline(startedAt);
            long modelStartedAt = nanoTime.getAsLong();
            log.info(
                    "event=agent_model_call_started traceId={} userId={} conversationId={} runId={} agentType={} round={}",
                    context.traceId(), context.userId(), context.conversationId(), context.runId(),
                    agentType, round);

            ModelTurn assistantTurn;
            try {
                assistantTurn = modelGateway.stream(
                        new ModelRequest(systemPrompt, modelProperties.getPromptVersion(), turns),
                        allowedTools,
                        delta -> publishDelta(context, sink, answer, startedAt, delta),
                        remainingDuration(startedAt));
                ensureWithinDeadline(startedAt);
            } catch (RuntimeException exception) {
                log.warn(
                        "event=agent_model_call_finished traceId={} userId={} conversationId={} runId={} agentType={} round={} durationMs={} status=failure errorCode={}",
                        context.traceId(), context.userId(), context.conversationId(), context.runId(),
                        agentType, round, elapsedMillis(modelStartedAt), stableCode(exception));
                throw exception;
            }
            log.info(
                    "event=agent_model_call_finished traceId={} userId={} conversationId={} runId={} agentType={} round={} durationMs={} status=success",
                    context.traceId(), context.userId(), context.conversationId(), context.runId(),
                    agentType, round, elapsedMillis(modelStartedAt));
            turns.add(assistantTurn);

            if (assistantTurn.toolCalls().isEmpty()) {
                return new SupportAgentResult(
                        answer.toString(), round, toolCallCount, modelProperties.getPromptVersion());
            }
            if (round == executionProperties.getMaxModelRounds()) {
                throw executionError("TOOL_LIMIT_EXCEEDED", "Agent 工具循环超过最大轮数", null);
            }

            List<ModelToolResult> toolResults = new ArrayList<>();
            for (ModelToolCall toolCall : assistantTurn.toolCalls()) {
                AgentTool tool = allowedTools.contains(toolCall.name())
                        ? toolRegistry.find(toolCall.name())
                        : null;
                if (tool == null) {
                    throw executionError("TOOL_NOT_ALLOWED", "模型请求了未允许的工具", null);
                }
                if (toolCallCount >= executionProperties.getMaxToolCalls()) {
                    throw executionError("TOOL_LIMIT_EXCEEDED", "Agent 工具调用次数超过上限", null);
                }
                ensureWithinDeadline(startedAt);
                toolCallCount++;
                toolResults.add(executeTool(context, sink, toolCall, tool, agentType, startedAt));
            }
            turns.add(ModelTurn.tool(toolResults));
        }
        throw executionError("TOOL_LIMIT_EXCEEDED", "Agent 工具循环超过最大轮数", null);
    }

    private void publishDelta(
            AgentExecutionContext context,
            AgentStreamSink sink,
            StringBuilder answer,
            long startedAt,
            String delta
    ) {
        ensureWithinDeadline(startedAt);
        if (delta == null || delta.isEmpty()) {
            return;
        }
        answer.append(delta);
        sink.messageDelta(delta);
    }

    private ModelToolResult executeTool(
            AgentExecutionContext context,
            AgentStreamSink sink,
            ModelToolCall toolCall,
            AgentTool tool,
            String agentType,
            long runStartedAt
    ) {
        long startedAt = nanoTime.getAsLong();
        sink.toolStarted(toolCall.id(), tool.name());
        log.info(
                "event=agent_tool_started traceId={} userId={} conversationId={} runId={} agentType={} toolName={}",
                context.traceId(), context.userId(), context.conversationId(), context.runId(),
                agentType, tool.name());
        try {
            String content = tool.execute(toolCall.arguments(), context, sink);
            long durationMs = elapsedMillis(startedAt);
            sink.toolCompleted(toolCall.id(), tool.name(), true, durationMs);
            log.info(
                    "event=agent_tool_finished traceId={} userId={} conversationId={} runId={} agentType={} toolName={} durationMs={} status=success",
                    context.traceId(), context.userId(), context.conversationId(), context.runId(),
                    agentType, tool.name(), durationMs);
            return new ModelToolResult(toolCall.id(), tool.name(), content);
        } catch (RuntimeException exception) {
            long durationMs = elapsedMillis(startedAt);
            sink.toolCompleted(toolCall.id(), tool.name(), false, durationMs);
            log.warn(
                    "event=agent_tool_finished traceId={} userId={} conversationId={} runId={} agentType={} toolName={} durationMs={} status=failure errorCode={}",
                    context.traceId(), context.userId(), context.conversationId(), context.runId(),
                    agentType, tool.name(), durationMs, stableCode(exception));
            throw exception;
        }
    }

    private void ensureWithinDeadline(long startedAt) {
        long elapsedNanos = nanoTime.getAsLong() - startedAt;
        if (elapsedNanos > executionProperties.getRunTimeout().toNanos()) {
            throw executionError("RUN_TIMEOUT", "Agent Run 已超时", null);
        }
    }

    private Duration remainingDuration(long startedAt) {
        long remainingNanos = executionProperties.getRunTimeout().toNanos()
                - Math.max(0L, nanoTime.getAsLong() - startedAt);
        if (remainingNanos <= 0L) {
            throw executionError("RUN_TIMEOUT", "Agent Run 已超时", null);
        }
        return Duration.ofNanos(remainingNanos);
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, nanoTime.getAsLong() - startedAt));
    }

    private AgentExecutionException executionError(String code, String message, Throwable cause) {
        return new AgentExecutionException(code, message, cause);
    }

    private String stableCode(RuntimeException exception) {
        return exception instanceof AgentExecutionException agentException
                ? agentException.getCode()
                : "INTERNAL_ERROR";
    }
}
