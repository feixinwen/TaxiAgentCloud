package com.fancy.taxiagent.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fancy.taxiagent.agent.config.AgentExecutionProperties;
import com.fancy.taxiagent.agent.agent.DailyAgent;
import com.fancy.taxiagent.agent.agent.FallbackAgent;
import com.fancy.taxiagent.agent.agent.OrderAgent;
import com.fancy.taxiagent.agent.agent.SupportAgent;
import com.fancy.taxiagent.agent.domain.dto.CreateConversationResponse;
import com.fancy.taxiagent.agent.domain.dto.SendMessageRequest;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.domain.entity.AgentConversation;
import com.fancy.taxiagent.agent.domain.model.SupportAgentResult;
import com.fancy.taxiagent.agent.domain.enums.ConversationStatus;
import com.fancy.taxiagent.agent.domain.model.PreparedAgentRun;
import com.fancy.taxiagent.agent.exception.AgentApiException;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.filter.RequestTraceFilter;
import com.fancy.taxiagent.agent.id.IdGenerator;
import com.fancy.taxiagent.agent.lock.ConversationRunLock;
import com.fancy.taxiagent.agent.mapper.AgentConversationMapper;
import com.fancy.taxiagent.agent.model.ModelToolCall;
import com.fancy.taxiagent.agent.model.ModelToolResult;
import com.fancy.taxiagent.agent.model.ModelTurn;
import com.fancy.taxiagent.agent.router.IntentRouter;
import com.fancy.taxiagent.agent.state.OrderStateService;
import com.fancy.taxiagent.agent.stream.AgentSseEvent;
import com.fancy.taxiagent.agent.stream.AgentSseSession;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 对话的应用服务。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final IdGenerator idGenerator;
    private final AgentConversationMapper conversationMapper;
    private final AgentRunPersistenceService runPersistenceService;
    private final ConversationRunLock conversationRunLock;
    private final AgentExecutionProperties executionProperties;
    private final IntentRouter intentRouter;
    private final SupportAgent supportAgent;
    private final DailyAgent dailyAgent;
    private final FallbackAgent fallbackAgent;
    private final OrderAgent orderAgent;
    private final OrderStateService orderStateService;
    private final ExecutorService agentRunExecutor;
    private final ScheduledExecutorService heartbeatScheduler;

    public AgentService(
            IdGenerator idGenerator,
            AgentConversationMapper conversationMapper,
            AgentRunPersistenceService runPersistenceService,
            ConversationRunLock conversationRunLock,
            AgentExecutionProperties executionProperties,
            IntentRouter intentRouter,
            SupportAgent supportAgent,
            DailyAgent dailyAgent,
            FallbackAgent fallbackAgent,
            OrderAgent orderAgent,
            OrderStateService orderStateService,
            ExecutorService agentRunExecutor,
            ScheduledExecutorService heartbeatScheduler
    ) {
        this.idGenerator = idGenerator;
        this.conversationMapper = conversationMapper;
        this.runPersistenceService = runPersistenceService;
        this.conversationRunLock = conversationRunLock;
        this.executionProperties = executionProperties;
        this.intentRouter = intentRouter;
        this.supportAgent = supportAgent;
        this.dailyAgent = dailyAgent;
        this.fallbackAgent = fallbackAgent;
        this.orderAgent = orderAgent;
        this.orderStateService = orderStateService;
        this.agentRunExecutor = agentRunExecutor;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    /**
     * 为当前登录用户创建一个新的活动对话。
     *
     * @param userId JWT subject 对应的用户 ID
     * @return 对外对话信息
     */
    @Transactional
    public CreateConversationResponse createConversation(long userId) {
        LocalDateTime now = LocalDateTime.now();
        AgentConversation conversation = new AgentConversation();
        conversation.setId(idGenerator.nextId());
        conversation.setConversationId(UUID.randomUUID().toString());
        conversation.setUserId(userId);
        conversation.setStatus(ConversationStatus.ACTIVE);
        conversation.setVersion(0);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        conversationMapper.insert(conversation);
        log.info(
                "event=agent_conversation_created traceId={} userId={} conversationId={}",
                MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY),
                userId,
                conversation.getConversationId()
        );
        return new CreateConversationResponse(
                conversation.getConversationId(),
                conversation.getStatus(),
                conversation.getCreatedAt()
        );
    }

    /**
     * 删除当前用户的对话（status 置 DELETED 的软删除，不加列不改表）。
     *
     * <p>列表查询排除 DELETED；历史/续发路径只认 ACTIVE，删除后自然 404。
     * 重复删除幂等：本人已删除的对话再次删除仍返回成功。</p>
     *
     * @param userId JWT subject 对应的用户 ID
     * @param conversationId 对外对话 ID
     */
    @Transactional
    public void deleteConversation(long userId, String conversationId) {
        AgentConversation conversation = conversationMapper.selectOne(
                new LambdaQueryWrapper<AgentConversation>()
                        .eq(AgentConversation::getConversationId, conversationId)
                        .eq(AgentConversation::getUserId, userId)
                        .last("LIMIT 1"));
        if (conversation == null) {
            throw new AgentApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "对话不存在");
        }
        if (conversation.getStatus() == ConversationStatus.DELETED) {
            return;
        }
        // 只 set status，避免全字段 updateById 覆盖并发 run 写入的 version/updatedAt
        conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getId, conversation.getId())
                .set(AgentConversation::getStatus, ConversationStatus.DELETED));
        log.info(
                "event=agent_conversation_deleted traceId={} userId={} conversationId={}",
                MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY),
                userId,
                conversationId
        );
    }

    /**
     * 为一次用户消息获取单对话锁，并在本地事务中准备消息与 Run。
     *
     * <p>准备成功后锁会保留给后续执行阶段，并由统一终止流程使用返回的 runId 释放；
     * 准备失败时由本方法立即释放。</p>
     *
     * @param userId JWT subject 对应的用户 ID
     * @param conversationId 对外对话 ID
     * @param request 用户消息请求
     * @return 已准备的运行上下文
     */
    public PreparedAgentRun prepareRun(long userId, String conversationId, SendMessageRequest request) {
        if (request == null || request.clientMessageId() == null) {
            throw invalidMessage();
        }
        runPersistenceService.findExistingRunId(userId, conversationId, request.clientMessageId())
                .ifPresent(existingRunId -> {
                    throw duplicateMessage(existingRunId);
                });

        String runId = UUID.randomUUID().toString();
        String traceId = MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY);
        boolean acquired = conversationRunLock.tryAcquire(
                conversationId,
                runId,
                executionProperties.getLockTtl()
        );
        if (!acquired) {
            runPersistenceService.findExistingRunId(userId, conversationId, request.clientMessageId())
                    .ifPresent(existingRunId -> {
                        throw duplicateMessage(existingRunId);
                    });
            throw conversationBusy();
        }

        try {
            return runPersistenceService.prepare(
                    userId,
                    conversationId,
                    request.clientMessageId(),
                    request.content(),
                    runId
            );
        } catch (RuntimeException exception) {
            releaseAfterPreparationFailure(conversationId, runId, userId, traceId, exception);
            throw exception;
        }
    }

    /**
     * 同步准备消息后创建 SSE 连接，并在虚拟线程中执行 SupportAgent。
     *
     * @param userId 已验证 JWT subject 对应的用户 ID
     * @param authorization 原始 Authorization 请求头，仅用于下游调用
     * @param traceId 请求链路追踪标识
     * @param conversationId 对外对话 ID
     * @param request 用户消息请求
     * @return 已建立的 SSE 发射器
     */
    public SseEmitter sendMessage(
            long userId,
            String authorization,
            String traceId,
            String conversationId,
            SendMessageRequest request
    ) {
        PreparedAgentRun prepared = prepareRun(userId, conversationId, request);
        return submitRun(userId, authorization, traceId, conversationId, prepared, this::executeRun);
    }

    /**
     * 恢复 HITL 暂停的订单确认流程：校验 break → 清 break → 复用 sendMessage 骨架提交 resume Run。
     *
     * <p>与 sendMessage 的唯一差异：提交 {@link #executeResumeRun}——不经过分类路由，
     * 在模型历史末尾注入用户确认的工具结果轮次后直接执行 OrderAgent。</p>
     *
     * @param userId JWT subject 对应的用户 ID
     * @param authorization 原始 Authorization 请求头，仅用于下游调用
     * @param traceId 请求链路追踪标识
     * @param conversationId 对外对话 ID
     * @param request 用户确认消息
     * @return 已建立的 SSE 发射器
     */
    public SseEmitter resume(
            long userId,
            String authorization,
            String traceId,
            String conversationId,
            SendMessageRequest request
    ) {
        if (!orderStateService.isBreak(conversationId)) {
            throw new AgentApiException(
                    HttpStatus.BAD_REQUEST, "ORDER_NOT_IN_CONFIRMATION", "当前没有待确认的订单", null);
        }
        PreparedAgentRun prepared = prepareRun(userId, conversationId, request);
        // 先 prepareRun（含对话归属校验）成功后再清 break：prepareRun 失败时保留确认状态，
        // 且不对外部可观察状态做未授权变更。
        orderStateService.setBreak(conversationId, false);
        log.info(
                "event=agent_run_resumed traceId={} userId={} conversationId={} runId={}",
                traceId, userId, conversationId, prepared.runId());
        return submitRun(userId, authorization, traceId, conversationId, prepared, this::executeResumeRun);
    }

    /**
     * 创建 SSE 会话、心跳与超时任务，并提交 Run 执行任务（sendMessage 与 resume 共用骨架）。
     *
     * <p>提交前出现调度或历史加载失败时统一走 {@link #failBeforeSseReturn} 收尾
     * （释放锁、以 503 拒绝）；终态竞争与后续失败处理由各终态方法保证一致。</p>
     *
     * @param userId 当前用户 ID
     * @param authorization 原始 Authorization 请求头，仅用于下游调用
     * @param traceId 请求链路追踪标识
     * @param conversationId 对外对话 ID
     * @param prepared 已提交的 Run 准备信息
     * @param execution 异步执行逻辑（executeRun 或 executeResumeRun）
     * @return 已建立的 SSE 发射器
     */
    private SseEmitter submitRun(
            long userId,
            String authorization,
            String traceId,
            String conversationId,
            PreparedAgentRun prepared,
            RunExecution execution
    ) {
        long runStartedAt = System.nanoTime();
        SseEmitter emitter = new SseEmitter(executionProperties.getSseTimeout().toMillis());
        AgentSseSession session = new AgentSseSession(
                emitter, heartbeatScheduler, executionProperties.getHeartbeatInterval(), prepared.runId());
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicReference<Future<?>> executionTask = new AtomicReference<>();
        session.onCancelled(() -> cancelRun(
                terminal, session, prepared, conversationId, userId, traceId, runStartedAt));
        session.onTransportFailure(() -> failRun(
                terminal, session, prepared, conversationId, userId, traceId, "INTERNAL_ERROR", null, runStartedAt));
        ScheduledFuture<?> deadlineTask;
        try {
            deadlineTask = heartbeatScheduler.schedule(() -> {
                Future<?> task = executionTask.get();
                if (task != null) {
                    task.cancel(true);
                }
                failRun(terminal, session, prepared, conversationId, userId, traceId,
                        "RUN_TIMEOUT", null, runStartedAt);
            }, executionProperties.getRunTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (deadlineTask != null) {
                session.bindDeadlineTask(deadlineTask);
            }
        } catch (RejectedExecutionException exception) {
            throw failBeforeSseReturn(
                    session, prepared, conversationId, userId, traceId, "AGENT_UNAVAILABLE");
        }
        List<ModelTurn> history;
        try {
            history = runPersistenceService.findHistoryBefore(
                    prepared.conversationDbId(), prepared.sequenceNo(), 30);
        } catch (RuntimeException exception) {
            throw failBeforeSseReturn(
                    session, prepared, conversationId, userId, traceId, "AGENT_UNAVAILABLE");
        }
        if (terminal.get()) {
            session.complete();
            throw unavailable();
        }

        AgentExecutionContext context = new AgentExecutionContext(
                userId,
                conversationId,
                prepared.runId(),
                authorization,
                traceId,
                prepared.content(),
                history
        );
        try {
            Future<?> submittedTask = agentRunExecutor.submit(() ->
                    execution.run(terminal, session, prepared, context, runStartedAt));
            executionTask.set(submittedTask);
            session.bindExecutionTask(submittedTask);
        } catch (RejectedExecutionException exception) {
            throw failBeforeSseReturn(
                    session, prepared, conversationId, userId, traceId, "AGENT_UNAVAILABLE");
        }
        return emitter;
    }

    private void executeRun(
            AtomicBoolean terminal,
            AgentSseSession session,
            PreparedAgentRun prepared,
            AgentExecutionContext context,
            long runStartedAt
    ) {
        executeWithRunContext(terminal, session, prepared, context, runStartedAt, () -> {
            IntentRouter.RouteDecision decision = intentRouter.route(
                    context.conversationId(), lastAssistantText(context), context.userMessage());
            switch (decision.kind()) {
                case "failure" -> completeRouteFailure(
                        terminal, session, prepared, context, decision.message(), runStartedAt);
                case "order_sticky" -> {
                    session.publish(AgentSseEvent.notify(prepared.runId(), decision.message()));
                    executeRoutedAgent(terminal, session, prepared, context, "ORDER", runStartedAt);
                }
                default -> executeRoutedAgent(
                        terminal, session, prepared, context, decision.agentType(), runStartedAt);
            }
        });
    }

    /**
     * 执行 HITL resume 的 Run：在模型历史末尾注入用户确认的工具结果后，直接执行 OrderAgent。
     *
     * <p>与 executeRun 的两处差异：不经 IntentRouter 分类（断点前已路由为 ORDER）；
     * history 末尾追加 confirmOrder 的工具结果轮次，使模型看到“摘要已生成 → 用户确认”的上下文。</p>
     *
     * @param terminal 终态竞争标记
     * @param session 当前 SSE 会话
     * @param prepared 已提交的 Run 准备信息
     * @param context 显式执行上下文
     * @param runStartedAt Run 开始纳秒时间
     */
    private void executeResumeRun(
            AtomicBoolean terminal,
            AgentSseSession session,
            PreparedAgentRun prepared,
            AgentExecutionContext context,
            long runStartedAt
    ) {
        executeWithRunContext(terminal, session, prepared, context, runStartedAt, () -> {
            AgentExecutionContext resumeContext = withConfirmFeedback(context);
            executeRoutedAgent(terminal, session, prepared, resumeContext, "ORDER", runStartedAt);
        });
    }

    /**
     * 在 Run 上下文中执行路由后的 Agent 逻辑：发布 runStarted、启动心跳，并统一处理取消/失败。
     *
     * @param terminal 终态竞争标记
     * @param session 当前 SSE 会话
     * @param prepared 已提交的 Run 准备信息
     * @param context 显式执行上下文
     * @param runStartedAt Run 开始纳秒时间
     * @param routedExecution 路由后的 Agent 执行逻辑
     */
    private void executeWithRunContext(
            AtomicBoolean terminal,
            AgentSseSession session,
            PreparedAgentRun prepared,
            AgentExecutionContext context,
            long runStartedAt,
            Runnable routedExecution
    ) {
        try (TraceMdcScope ignored = TraceMdcScope.open(context.traceId())) {
            session.publish(AgentSseEvent.runStarted(prepared.runId(), context.conversationId()));
            session.startHeartbeat();
            routedExecution.run();
        } catch (RuntimeException exception) {
            if (session.isCancelled()) {
                cancelRun(terminal, session, prepared, context.conversationId(), context.userId(),
                        context.traceId(), runStartedAt);
            } else {
                failRun(
                        terminal,
                        session,
                        prepared,
                        context.conversationId(),
                        context.userId(),
                        context.traceId(),
                        stableErrorCode(exception),
                        exception, runStartedAt
                );
            }
        }
    }

    /**
     * 在历史末尾追加用户确认的工具结果轮次（confirmOrder 哨兵注入点）。
     *
     * <p>toolCallId 取最近一次 confirmOrder 调用写入的哨兵值，缺失时回退为工具名；
     * 工具结果正文为用户确认消息。历史冻结于 AgentExecutionContext 构造，因此重建上下文。</p>
     *
     * @param context 原始执行上下文
     * @return 注入确认反馈后的新执行上下文
     */
    private AgentExecutionContext withConfirmFeedback(AgentExecutionContext context) {
        String toolCallId = orderStateService.getLastConfirmToolCallId(context.conversationId());
        String confirmToolCallId = toolCallId == null || toolCallId.isBlank() ? "confirmOrder" : toolCallId;
        List<ModelTurn> history = new ArrayList<>(context.history());
        // 先补一条合成 assistant 工具调用轮：OpenAI 兼容模型拒绝"无前驱 tool_call 的 tool 消息"。
        history.add(ModelTurn.assistant("", List.of(
                new ModelToolCall(confirmToolCallId, "confirmOrder", "{}"))));
        history.add(ModelTurn.tool(List.of(
                new ModelToolResult(confirmToolCallId, "confirmOrder", context.userMessage()))));
        return new AgentExecutionContext(
                context.userId(),
                context.conversationId(),
                context.runId(),
                context.authorization(),
                context.traceId(),
                context.userMessage(),
                history
        );
    }

    /**
     * 按 Agent 类型委托对应 Agent 执行，各 Agent 内部使用共享 AgentLoop 与各自的工具白名单。
     *
     * @param agentType 目标 Agent 类型（ORDER/DAILY/SUPPORT/OTHER）
     * @param context 显式执行上下文
     * @param sink 公开流事件出口
     * @return 聚合后的执行结果
     */
    private SupportAgentResult routeTo(String agentType, AgentExecutionContext context, AgentStreamSink sink) {
        return switch (agentType) {
            case "ORDER" -> orderAgent.execute(context, sink);
            case "DAILY" -> dailyAgent.execute(context, sink);
            case "SUPPORT" -> supportAgent.execute(context, sink);
            case "OTHER" -> fallbackAgent.execute(context, sink);
            default -> throw new AgentExecutionException("UNKNOWN_AGENT_TYPE", "未知 Agent 类型", null);
        };
    }

    /**
     * 执行路由到的 Agent：先修正 Run 审计表中的 agent_type，再委托 Agent 执行并进入完成路径。
     *
     * <p>agent_type 修正为尽力而为：Run 若已被终态竞争终结（如并发超时）则更新失败，
     * 此时以插入阶段的占位值留存审计记录，不阻塞本轮执行。</p>
     *
     * @param terminal 终态竞争标记
     * @param session 当前 SSE 会话
     * @param prepared 已提交的 Run 准备信息
     * @param context 显式执行上下文
     * @param routeLabel 路由标签（ORDER/DAILY/SUPPORT/OTHER）
     * @param startedAt Run 开始纳秒时间
     */
    private void executeRoutedAgent(
            AtomicBoolean terminal,
            AgentSseSession session,
            PreparedAgentRun prepared,
            AgentExecutionContext context,
            String routeLabel,
            long startedAt
    ) {
        String agentType = routedAgentType(routeLabel);
        if (!runPersistenceService.setAgentType(prepared.runDbId(), agentType)) {
            log.warn(
                    "event=agent_run_agent_type_update_failed traceId={} userId={} conversationId={} runId={} agentType={}",
                    context.traceId(), context.userId(), context.conversationId(), prepared.runId(), agentType);
        }
        completeRun(terminal, session, prepared, context,
                routeTo(routeLabel, context, new SessionStreamSink(session)), startedAt);
    }

    /**
     * 把路由标签映射为实际执行 Agent 的类型标签（用于审计表与日志）。
     *
     * @param routeLabel 路由标签（ORDER/DAILY/SUPPORT/OTHER）
     * @return 实际 Agent 类型（ORDER/DAILY/SUPPORT/FALLBACK）
     */
    private String routedAgentType(String routeLabel) {
        return switch (routeLabel) {
            case "ORDER" -> orderAgent.agentType();
            case "DAILY" -> dailyAgent.agentType();
            case "SUPPORT" -> supportAgent.agentType();
            case "OTHER" -> fallbackAgent.agentType();
            default -> throw new AgentExecutionException("UNKNOWN_AGENT_TYPE", "未知 Agent 类型", null);
        };
    }

    /**
     * 从 Run 历史中取最近一条非空 ASSISTANT 正文，供分类器注入上下文。
     *
     * <p>历史按时间正序排列；路由失败等不落库场景没有助手消息，返回 null。</p>
     *
     * @param context 显式执行上下文
     * @return 最近一条助手正文；无则 null
     */
    private String lastAssistantText(AgentExecutionContext context) {
        List<ModelTurn> history = context.history();
        for (int index = history.size() - 1; index >= 0; index--) {
            ModelTurn turn = history.get(index);
            if ("assistant".equals(turn.role()) && !turn.content().isBlank()) {
                return turn.content();
            }
        }
        return null;
    }

    /**
     * 路由失败（分类不可用/DANGER）的完成路径：向客户端发固定话术并以 COMPLETED 终结 Run，
     * 但不落库助手消息。
     *
     * @param terminal 终态竞争标记
     * @param session 当前 SSE 会话
     * @param prepared 已提交的 Run 准备信息
     * @param context 显式执行上下文
     * @param message 固定话术正文
     * @param startedAt Run 开始纳秒时间
     */
    private void completeRouteFailure(
            AtomicBoolean terminal,
            AgentSseSession session,
            PreparedAgentRun prepared,
            AgentExecutionContext context,
            String message,
            long startedAt
    ) {
        boolean persistenceWon;
        try {
            persistenceWon = runPersistenceService.completeWithoutMessage(prepared);
        } catch (RuntimeException exception) {
            failRun(terminal, session, prepared, context.conversationId(), context.userId(), context.traceId(),
                    stableErrorCode(exception), exception, startedAt);
            return;
        }
        if (!persistenceWon || !terminal.compareAndSet(false, true)) {
            return;
        }
        try {
            session.publish(AgentSseEvent.messageDelta(prepared.runId(), message));
            session.publish(AgentSseEvent.runCompleted(prepared.runId()));
            log.info(
                    "event=agent_run_finished traceId={} userId={} conversationId={} runId={} status=COMPLETED durationMs={}",
                    context.traceId(), context.userId(), context.conversationId(), prepared.runId(), elapsedMillis(startedAt));
            session.complete();
        } catch (RuntimeException exception) {
            log.warn("event=agent_run_finished traceId={} userId={} conversationId={} runId={} status=COMPLETED durationMs={}",
                    context.traceId(), context.userId(), context.conversationId(), prepared.runId(), elapsedMillis(startedAt));
        } finally {
            releaseLock(context.conversationId(), prepared.runId(), context.userId(), context.traceId());
            session.complete();
        }
    }

    private void completeRun(
            AtomicBoolean terminal,
            AgentSseSession session,
            PreparedAgentRun prepared,
            AgentExecutionContext context,
            SupportAgentResult result,
            long startedAt
    ) {
        long assistantMessageId;
        try {
            assistantMessageId = runPersistenceService.complete(prepared, result);
            if (assistantMessageId == 0L) {
                return;
            }
        } catch (RuntimeException exception) {
            failRun(terminal, session, prepared, context.conversationId(), context.userId(), context.traceId(),
                    stableErrorCode(exception), exception, startedAt);
            return;
        }
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        try {
            session.publish(AgentSseEvent.messageCompleted(prepared.runId(), assistantMessageId));
            session.publish(AgentSseEvent.runCompleted(prepared.runId()));
            log.info(
                    "event=agent_run_finished traceId={} userId={} conversationId={} runId={} status=COMPLETED durationMs={}",
                    context.traceId(), context.userId(), context.conversationId(), prepared.runId(), elapsedMillis(startedAt));
            session.complete();
        } catch (RuntimeException exception) {
            log.warn("event=agent_run_finished traceId={} userId={} conversationId={} runId={} status=COMPLETED durationMs={}",
                    context.traceId(), context.userId(), context.conversationId(), prepared.runId(), elapsedMillis(startedAt));
        } finally {
            releaseLock(context.conversationId(), prepared.runId(), context.userId(), context.traceId());
            session.complete();
        }
    }

    private void failRun(
            AtomicBoolean terminal,
            AgentSseSession session,
            PreparedAgentRun prepared,
            String conversationId,
            long userId,
            String traceId,
            String errorCode,
            RuntimeException exception,
        long startedAt
    ) {
        try (TraceMdcScope ignored = TraceMdcScope.open(traceId)) {
            if (terminal.get()) {
                return;
            }
            boolean persistenceWon = false;
            boolean shouldClose = false;
            try {
                try {
                    persistenceWon = runPersistenceService.fail(prepared, errorCode);
                } catch (RuntimeException persistenceException) {
                    terminal.compareAndSet(false, true);
                    shouldClose = true;
                    logTerminalPersistenceFailure(
                            traceId, userId, conversationId, prepared.runId(), errorCode, persistenceException);
                    return;
                }
                if (!persistenceWon) {
                    return;
                }
                shouldClose = true;
                if (!terminal.compareAndSet(false, true)) {
                    return;
                }
                if (!session.isTerminated()) {
                    session.publish(AgentSseEvent.runFailed(prepared.runId(), errorCode, "请求处理失败"));
                }
            } catch (RuntimeException transportException) {
                if (exception != null) {
                    exception.addSuppressed(transportException);
                }
            } finally {
                if (shouldClose) {
                    closeSessionAndReleaseLock(
                            session, conversationId, prepared.runId(), userId, traceId);
                }
                if (persistenceWon) {
                    log.warn(
                            "event=agent_run_finished traceId={} userId={} conversationId={} runId={} status=FAILED errorCode={} durationMs={}",
                            traceId, userId, conversationId, prepared.runId(), errorCode, elapsedMillis(startedAt));
                }
            }
        }
    }

    private void cancelRun(
            AtomicBoolean terminal,
            AgentSseSession session,
            PreparedAgentRun prepared,
            String conversationId,
            long userId,
            String traceId,
            long startedAt
    ) {
        try (TraceMdcScope ignored = TraceMdcScope.open(traceId)) {
            if (terminal.get()) {
                return;
            }
            boolean persistenceWon = false;
            boolean shouldClose = false;
            try {
                try {
                    persistenceWon = runPersistenceService.cancel(prepared);
                } catch (RuntimeException persistenceException) {
                    terminal.compareAndSet(false, true);
                    shouldClose = true;
                    logTerminalPersistenceFailure(
                            traceId, userId, conversationId, prepared.runId(), "CLIENT_DISCONNECTED", persistenceException);
                    return;
                }
                if (!persistenceWon) {
                    return;
                }
                shouldClose = true;
                if (!terminal.compareAndSet(false, true)) {
                    return;
                }
            } finally {
                if (shouldClose) {
                    closeSessionAndReleaseLock(
                            session, conversationId, prepared.runId(), userId, traceId);
                }
                if (persistenceWon) {
                    log.info(
                            "event=agent_run_finished traceId={} userId={} conversationId={} runId={} status=CANCELLED durationMs={}",
                            traceId, userId, conversationId, prepared.runId(), elapsedMillis(startedAt));
                }
            }
        }
    }

    private AgentApiException failBeforeSseReturn(
            AgentSseSession session,
            PreparedAgentRun prepared,
            String conversationId,
            long userId,
            String traceId,
            String errorCode
    ) {
        try (TraceMdcScope ignored = TraceMdcScope.open(traceId)) {
            try {
                runPersistenceService.fail(prepared, errorCode);
            } catch (RuntimeException persistenceException) {
                logTerminalPersistenceFailure(
                        traceId,
                        userId,
                        conversationId,
                        prepared.runId(),
                        errorCode,
                        persistenceException
                );
            } finally {
                closeSessionAndReleaseLock(
                        session, conversationId, prepared.runId(), userId, traceId);
                log.warn(
                        "event=agent_run_finished traceId={} userId={} conversationId={} runId={} status=FAILED errorCode={} durationMs=0",
                        traceId, userId, conversationId, prepared.runId(), errorCode);
            }
        }
        return unavailable();
    }

    private void closeSessionAndReleaseLock(
            AgentSseSession session,
            String conversationId,
            String runId,
            long userId,
            String traceId
    ) {
        try {
            session.complete();
        } finally {
            releaseLock(conversationId, runId, userId, traceId);
        }
    }

    private void logTerminalPersistenceFailure(
            String traceId,
            long userId,
            String conversationId,
            String runId,
            String errorCode,
            RuntimeException exception
    ) {
        log.error(
                "event=agent_run_terminal_persistence_failed traceId={} userId={} conversationId={} runId={} status=UNKNOWN errorCode={} exceptionType={}",
                traceId, userId, conversationId, runId, errorCode, exception.getClass().getSimpleName());
    }

    private void releaseLock(String conversationId, String runId, long userId, String traceId) {
        try (TraceMdcScope ignored = TraceMdcScope.open(traceId)) {
            try {
                conversationRunLock.release(conversationId, runId);
            } catch (RuntimeException exception) {
                log.error(
                        "event=agent_run_lock_release_failed traceId={} userId={} conversationId={} runId={} status=FAILED errorCode=LOCK_RELEASE_FAILED durationMs=0 exceptionType={}",
                        traceId, userId, conversationId, runId, exception.getClass().getSimpleName());
            }
        }
    }

    private long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedAt));
    }

    private String stableErrorCode(RuntimeException exception) {
        return exception instanceof AgentExecutionException agentException
                ? agentException.getCode()
                : "INTERNAL_ERROR";
    }

    private AgentApiException unavailable() {
        return new AgentApiException(HttpStatus.SERVICE_UNAVAILABLE, "AGENT_UNAVAILABLE", "Agent 服务暂不可用");
    }

    /**
     * 提交到执行线程池的 Run 执行策略：sendMessage 与 resume 共用 submitRun 骨架的唯一差异点。
     */
    @FunctionalInterface
    private interface RunExecution {
        void run(
                AtomicBoolean terminal,
                AgentSseSession session,
                PreparedAgentRun prepared,
                AgentExecutionContext context,
                long runStartedAt
        );
    }

    private static final class SessionStreamSink implements AgentStreamSink {

        private final AgentSseSession session;

        private SessionStreamSink(AgentSseSession session) {
            this.session = session;
        }

        @Override
        public void toolStarted(String toolCallId, String toolName) {
            session.publish(AgentSseEvent.toolStarted(session.runId(), toolCallId, toolName));
        }

        @Override
        public void toolCompleted(String toolCallId, String toolName, boolean success, long durationMs) {
            session.publish(AgentSseEvent.toolCompleted(session.runId(), toolCallId, toolName, success, durationMs));
        }

        @Override
        public void messageDelta(String content) {
            session.publish(AgentSseEvent.messageDelta(session.runId(), content));
        }

        @Override
        public void confirm(String content, Map<String, Object> route) {
            session.publish(AgentSseEvent.confirm(session.runId(), content, route));
        }
    }

    private void releaseAfterPreparationFailure(
            String conversationId,
            String runId,
            long userId,
            String traceId,
            RuntimeException preparationException
    ) {
        try (TraceMdcScope ignored = TraceMdcScope.open(traceId)) {
            try {
                conversationRunLock.release(conversationId, runId);
            } catch (RuntimeException releaseException) {
                preparationException.addSuppressed(releaseException);
                log.error(
                        "event=agent_prepare_lock_release_failed traceId={} userId={} conversationId={} runId={} status=FAILED errorCode=LOCK_RELEASE_FAILED durationMs=0 exceptionType={}",
                        traceId,
                        userId,
                        conversationId,
                        runId,
                        releaseException.getClass().getSimpleName()
                );
            }
        }
    }

    private static final class TraceMdcScope implements AutoCloseable {

        private final Map<String, String> previousContext;

        private TraceMdcScope(String traceId) {
            previousContext = MDC.getCopyOfContextMap();
            MDC.clear();
            if (traceId != null && !traceId.isBlank()) {
                MDC.put(RequestTraceFilter.TRACE_ID_MDC_KEY, traceId);
            }
        }

        private static TraceMdcScope open(String traceId) {
            return new TraceMdcScope(traceId);
        }

        @Override
        public void close() {
            MDC.clear();
            if (previousContext != null && !previousContext.isEmpty()) {
                MDC.setContextMap(previousContext);
            }
        }
    }

    private AgentApiException invalidMessage() {
        return new AgentApiException(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE", "消息内容或标识不合法");
    }

    private AgentApiException duplicateMessage(String runId) {
        return new AgentApiException(
                HttpStatus.CONFLICT,
                "DUPLICATE_MESSAGE",
                "clientMessageId 已处理",
                runId
        );
    }

    private AgentApiException conversationBusy() {
        return new AgentApiException(HttpStatus.CONFLICT, "CONVERSATION_BUSY", "对话正在处理中");
    }
}
