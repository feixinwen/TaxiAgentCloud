package com.fancy.taxiagent.agent.service;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fancy.taxiagent.agent.config.AgentExecutionProperties;
import com.fancy.taxiagent.agent.agent.DailyAgent;
import com.fancy.taxiagent.agent.agent.FallbackAgent;
import com.fancy.taxiagent.agent.agent.OrderAgent;
import com.fancy.taxiagent.agent.agent.SupportAgent;
import com.fancy.taxiagent.agent.domain.dto.CreateConversationResponse;
import com.fancy.taxiagent.agent.domain.dto.SendMessageRequest;
import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.domain.model.PreparedAgentRun;
import com.fancy.taxiagent.agent.domain.model.SupportAgentResult;
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
import com.fancy.taxiagent.agent.stream.AgentStreamSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证消息准备阶段的 Redis 锁与 MySQL 事务编排。
 */
@ExtendWith(MockitoExtension.class)
class AgentServiceTest {

    private static final long USER_ID = 50001L;
    private static final String CONVERSATION_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private AgentConversationMapper conversationMapper;

    @Mock
    private AgentRunPersistenceService persistenceService;

    @Mock
    private ConversationRunLock conversationRunLock;

    @Mock
    private IntentRouter intentRouter;

    @Mock
    private SupportAgent supportAgent;

    @Mock
    private DailyAgent dailyAgent;

    @Mock
    private FallbackAgent fallbackAgent;

    @Mock
    private OrderAgent orderAgent;

    @Mock
    private OrderStateService orderStateService;

    @Mock
    private ExecutorService agentRunExecutor;

    @Mock
    private ScheduledExecutorService heartbeatScheduler;

    private AgentService agentService;

    @BeforeEach
    void setUp() {
        AgentExecutionProperties properties = new AgentExecutionProperties();
        agentService = new AgentService(
                idGenerator,
                conversationMapper,
                persistenceService,
                conversationRunLock,
                properties,
                intentRouter,
                supportAgent,
                dailyAgent,
                fallbackAgent,
                orderAgent,
                orderStateService,
                agentRunExecutor,
                heartbeatScheduler
        );
        // 默认路由到 SUPPORT，保持既有用例语义；具体路由用例在测试内重新打桩覆盖。
        org.mockito.Mockito.lenient().when(intentRouter.route(
                        anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        anyString()))
                .thenReturn(IntentRouter.RouteDecision.agent("SUPPORT"));
    }

    @Test
    void shouldAcquireConfiguredLockBeforePreparingRun() {
        UUID clientMessageId = UUID.randomUUID();
        SendMessageRequest request = new SendMessageRequest(clientMessageId, "测试消息");
        when(persistenceService.findExistingRunId(USER_ID, CONVERSATION_ID, clientMessageId))
                .thenReturn(Optional.empty());
        when(conversationRunLock.tryAcquire(
                org.mockito.ArgumentMatchers.eq(CONVERSATION_ID),
                anyString(),
                org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(90))))
                .thenReturn(true);
        ArgumentCaptor<String> runIdCaptor = ArgumentCaptor.forClass(String.class);
        PreparedAgentRun expected = new PreparedAgentRun(1L, 2L, 3L, "ignored", "测试消息", 1L);
        when(persistenceService.prepare(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(CONVERSATION_ID),
                org.mockito.ArgumentMatchers.eq(clientMessageId),
                org.mockito.ArgumentMatchers.eq("测试消息"),
                anyString()))
                .thenReturn(expected);

        PreparedAgentRun actual = agentService.prepareRun(USER_ID, CONVERSATION_ID, request);

        assertThat(actual).isSameAs(expected);
        verify(conversationRunLock).tryAcquire(
                org.mockito.ArgumentMatchers.eq(CONVERSATION_ID),
                runIdCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(90))
        );
        verify(persistenceService).prepare(
                USER_ID,
                CONVERSATION_ID,
                clientMessageId,
                "测试消息",
                runIdCaptor.getValue()
        );
        verify(conversationRunLock, never()).release(CONVERSATION_ID, runIdCaptor.getValue());
    }

    @Test
    void shouldIncludeCurrentTraceIdInConversationCreatedLog() {
        when(idGenerator.nextId()).thenReturn(701L);
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AgentService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.put("traceId", "trace-create-701");

        try {
            CreateConversationResponse response = agentService.createConversation(USER_ID);

            assertThat(response.conversationId()).isNotBlank();
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains(
                                    "event=agent_conversation_created",
                                    "traceId=trace-create-701",
                                    "userId=50001"));
        } finally {
            MDC.remove("traceId");
            logger.detachAppender(appender);
        }
    }

    @Test
    void shouldReleaseOwnedLockWhenPersistencePreparationFails() {
        UUID clientMessageId = UUID.randomUUID();
        SendMessageRequest request = new SendMessageRequest(clientMessageId, "测试消息");
        when(persistenceService.findExistingRunId(USER_ID, CONVERSATION_ID, clientMessageId))
                .thenReturn(Optional.empty());
        when(conversationRunLock.tryAcquire(
                org.mockito.ArgumentMatchers.eq(CONVERSATION_ID),
                anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenReturn(true);
        when(persistenceService.prepare(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(CONVERSATION_ID),
                org.mockito.ArgumentMatchers.eq(clientMessageId),
                org.mockito.ArgumentMatchers.eq("测试消息"),
                anyString()))
                .thenThrow(new IllegalStateException("database failed"));
        ArgumentCaptor<String> runIdCaptor = ArgumentCaptor.forClass(String.class);

        assertThatThrownBy(() -> agentService.prepareRun(USER_ID, CONVERSATION_ID, request))
                .isInstanceOf(IllegalStateException.class);

        verify(conversationRunLock).tryAcquire(
                org.mockito.ArgumentMatchers.eq(CONVERSATION_ID),
                runIdCaptor.capture(),
                org.mockito.ArgumentMatchers.any(Duration.class)
        );
        verify(conversationRunLock).release(CONVERSATION_ID, runIdCaptor.getValue());
    }

    @Test
    void shouldReturnExistingRunBeforeTryingToAcquireLock() {
        UUID clientMessageId = UUID.randomUUID();
        String existingRunId = UUID.randomUUID().toString();
        when(persistenceService.findExistingRunId(USER_ID, CONVERSATION_ID, clientMessageId))
                .thenReturn(Optional.of(existingRunId));

        assertThatThrownBy(() -> agentService.prepareRun(
                USER_ID,
                CONVERSATION_ID,
                new SendMessageRequest(clientMessageId, "重复消息")
        )).isInstanceOfSatisfying(AgentApiException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo("DUPLICATE_MESSAGE");
            assertThat(exception.getRunId()).isEqualTo(existingRunId);
        });

        verify(conversationRunLock, never()).tryAcquire(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class)
        );
    }

    @Test
    void shouldReturnConversationBusyWhenLockIsOwnedByAnotherRun() {
        UUID clientMessageId = UUID.randomUUID();
        when(persistenceService.findExistingRunId(USER_ID, CONVERSATION_ID, clientMessageId))
                .thenReturn(Optional.empty());
        when(conversationRunLock.tryAcquire(
                org.mockito.ArgumentMatchers.eq(CONVERSATION_ID),
                anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> agentService.prepareRun(
                USER_ID,
                CONVERSATION_ID,
                new SendMessageRequest(clientMessageId, "新消息")
        )).isInstanceOfSatisfying(AgentApiException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("CONVERSATION_BUSY"));

        verify(persistenceService, never()).prepare(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void shouldReturnExistingRunFoundBySecondCheckAfterLockConflict() {
        UUID clientMessageId = UUID.randomUUID();
        String existingRunId = UUID.randomUUID().toString();
        when(persistenceService.findExistingRunId(USER_ID, CONVERSATION_ID, clientMessageId))
                .thenReturn(Optional.empty(), Optional.of(existingRunId));
        when(conversationRunLock.tryAcquire(
                org.mockito.ArgumentMatchers.eq(CONVERSATION_ID),
                anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> agentService.prepareRun(
                USER_ID,
                CONVERSATION_ID,
                new SendMessageRequest(clientMessageId, "重复消息")
        )).isInstanceOfSatisfying(AgentApiException.class, exception -> {
            assertThat(exception.getCode()).isEqualTo("DUPLICATE_MESSAGE");
            assertThat(exception.getRunId()).isEqualTo(existingRunId);
        });

        verify(persistenceService, never()).prepare(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(UUID.class),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void shouldPersistFailureAndReleaseLockWhenExecutorRejectsBeforeSseIsReturned() {
        UUID clientMessageId = UUID.randomUUID();
        PreparedAgentRun prepared = new PreparedAgentRun(
                1L, 2L, 3L, "run-rejected", "测试消息", 1L);
        when(persistenceService.findExistingRunId(USER_ID, CONVERSATION_ID, clientMessageId))
                .thenReturn(Optional.empty());
        when(conversationRunLock.tryAcquire(
                org.mockito.ArgumentMatchers.eq(CONVERSATION_ID),
                anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenReturn(true);
        when(persistenceService.prepare(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(CONVERSATION_ID),
                org.mockito.ArgumentMatchers.eq(clientMessageId),
                org.mockito.ArgumentMatchers.eq("测试消息"),
                anyString()))
                .thenReturn(prepared);
        when(agentRunExecutor.submit(org.mockito.ArgumentMatchers.any(Runnable.class)))
                .thenThrow(new RejectedExecutionException("overloaded"));
        AtomicReference<String> releaseTraceId = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            releaseTraceId.set(MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY));
            return null;
        }).when(conversationRunLock).release(CONVERSATION_ID, prepared.runId());

        assertThatThrownBy(() -> agentService.sendMessage(
                USER_ID, "Bearer secret", "trace-001", CONVERSATION_ID,
                new SendMessageRequest(clientMessageId, "测试消息")
        )).isInstanceOfSatisfying(AgentApiException.class, exception -> {
            assertThat(exception.getStatus().value()).isEqualTo(503);
            assertThat(exception.getCode()).isEqualTo("AGENT_UNAVAILABLE");
        });

        verify(persistenceService).fail(prepared, "AGENT_UNAVAILABLE");
        verify(conversationRunLock).release(CONVERSATION_ID, prepared.runId());
        assertThat(releaseTraceId).hasValue("trace-001");
    }

    @Test
    void shouldKeepStableHttpAndReleaseResourcesWhenPreSseFailureCannotBePersisted() {
        UUID clientMessageId = UUID.randomUUID();
        PreparedAgentRun prepared = new PreparedAgentRun(
                1L, 2L, 3L, "run-rejected-persistence-error", "测试消息", 1L);
        when(persistenceService.findExistingRunId(USER_ID, CONVERSATION_ID, clientMessageId))
                .thenReturn(Optional.empty());
        when(conversationRunLock.tryAcquire(
                org.mockito.ArgumentMatchers.eq(CONVERSATION_ID),
                anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenReturn(true);
        when(persistenceService.prepare(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(CONVERSATION_ID),
                org.mockito.ArgumentMatchers.eq(clientMessageId),
                org.mockito.ArgumentMatchers.eq("测试消息"),
                anyString()))
                .thenReturn(prepared);
        ScheduledFuture<?> deadline = org.mockito.Mockito.mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(deadline).when(heartbeatScheduler).schedule(
                org.mockito.ArgumentMatchers.any(Runnable.class),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(TimeUnit.class));
        when(agentRunExecutor.submit(org.mockito.ArgumentMatchers.any(Runnable.class)))
                .thenThrow(new RejectedExecutionException("overloaded"));
        when(persistenceService.fail(prepared, "AGENT_UNAVAILABLE"))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> agentService.sendMessage(
                USER_ID, "Bearer secret", "trace-001", CONVERSATION_ID,
                new SendMessageRequest(clientMessageId, "测试消息")
        )).isInstanceOfSatisfying(AgentApiException.class, exception -> {
            assertThat(exception.getStatus().value()).isEqualTo(503);
            assertThat(exception.getCode()).isEqualTo("AGENT_UNAVAILABLE");
        });

        verify(deadline).cancel(false);
        verify(conversationRunLock).release(CONVERSATION_ID, prepared.runId());
    }

    @Test
    void shouldFailRunTimeoutAndInterruptExecutionAtConfiguredDeadline() {
        UUID clientMessageId = UUID.randomUUID();
        PreparedAgentRun prepared = new PreparedAgentRun(1L, 2L, 3L, "run-timeout", "测试消息", 1L);
        when(persistenceService.findExistingRunId(USER_ID, CONVERSATION_ID, clientMessageId)).thenReturn(Optional.empty());
        when(conversationRunLock.tryAcquire(org.mockito.ArgumentMatchers.eq(CONVERSATION_ID), anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class))).thenReturn(true);
        when(persistenceService.prepare(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), anyString())).thenReturn(prepared);
        Future<?> future = org.mockito.Mockito.mock(Future.class);
        org.mockito.Mockito.doReturn(future).when(agentRunExecutor)
                .submit(org.mockito.ArgumentMatchers.any(Runnable.class));
        ScheduledFuture<?> deadline = org.mockito.Mockito.mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> deadlineTask = ArgumentCaptor.forClass(Runnable.class);
        org.mockito.Mockito.doReturn(deadline).when(heartbeatScheduler)
                .schedule(deadlineTask.capture(), org.mockito.ArgumentMatchers.eq(60_000L),
                        org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS));
        when(persistenceService.fail(prepared, "RUN_TIMEOUT")).thenReturn(true);
        AtomicReference<String> releaseTraceId = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            releaseTraceId.set(MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY));
            return null;
        }).when(conversationRunLock).release(CONVERSATION_ID, prepared.runId());

        SseEmitter emitter = agentService.sendMessage(USER_ID, "Bearer private", "trace-001", CONVERSATION_ID,
                new SendMessageRequest(clientMessageId, "测试消息"));
        MDC.put("outer-mdc", "deadline-sentinel");
        try {
            deadlineTask.getValue().run();
            assertThat(MDC.getCopyOfContextMap())
                    .containsExactlyEntriesOf(Map.of("outer-mdc", "deadline-sentinel"));
        } finally {
            MDC.clear();
        }

        assertThat(eventNames(emitter)).containsExactly("run.failed");
        assertThat(eventPayloads(emitter))
                .singleElement()
                .satisfies(payload -> assertThat(payload.get("errorCode")).isEqualTo("RUN_TIMEOUT"));
        verify(future).cancel(true);
        verify(deadline).cancel(false);
        verify(persistenceService).fail(prepared, "RUN_TIMEOUT");
        verify(conversationRunLock).release(CONVERSATION_ID, prepared.runId());
        assertThat(releaseTraceId).hasValue("trace-001");
    }

    @Test
    void shouldExecuteSubmittedRunnableThenPersistCompletionBeforeReleasingLock() {
        UUID clientMessageId = UUID.randomUUID();
        PreparedAgentRun prepared = new PreparedAgentRun(1L, 2L, 3L, "run-success", "测试消息", 1L);
        when(persistenceService.findExistingRunId(USER_ID, CONVERSATION_ID, clientMessageId)).thenReturn(Optional.empty());
        when(conversationRunLock.tryAcquire(org.mockito.ArgumentMatchers.eq(CONVERSATION_ID), anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class))).thenReturn(true);
        when(persistenceService.prepare(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), anyString())).thenReturn(prepared);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        Future<?> future = org.mockito.Mockito.mock(Future.class);
        org.mockito.Mockito.doReturn(future).when(agentRunExecutor).submit(task.capture());
        org.mockito.Mockito.doReturn(org.mockito.Mockito.mock(ScheduledFuture.class)).when(heartbeatScheduler)
                .schedule(org.mockito.ArgumentMatchers.any(Runnable.class), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(TimeUnit.class));
        when(supportAgent.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.fancy.taxiagent.agent.domain.model.SupportAgentResult("答案", 1, 0, "support-v1"));
        when(persistenceService.complete(org.mockito.ArgumentMatchers.eq(prepared), org.mockito.ArgumentMatchers.any()))
                .thenReturn(4L);

        agentService.sendMessage(USER_ID, "Bearer private", "trace-001", CONVERSATION_ID,
                new SendMessageRequest(clientMessageId, "测试消息"));
        task.getValue().run();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(persistenceService, conversationRunLock);
        order.verify(persistenceService).complete(org.mockito.ArgumentMatchers.eq(prepared), org.mockito.ArgumentMatchers.any());
        order.verify(conversationRunLock).release(CONVERSATION_ID, prepared.runId());
    }

    @Test
    void shouldPublishSuccessfulPublicEventsInOrderAndCancelScheduledResources() {
        ScheduledFuture<?> heartbeat = org.mockito.Mockito.mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(heartbeat).when(heartbeatScheduler).scheduleWithFixedDelay(
                org.mockito.ArgumentMatchers.any(Runnable.class),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(TimeUnit.class));
        when(supportAgent.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    AgentStreamSink sink = invocation.getArgument(1);
                    sink.toolStarted("tool-call-001", "searchKnowledgeBase");
                    sink.toolCompleted("tool-call-001", "searchKnowledgeBase", true, 12L);
                    sink.messageDelta("公开答复");
                    return new SupportAgentResult("公开答复", 2, 1, "support-v1");
                });
        when(persistenceService.complete(
                org.mockito.ArgumentMatchers.any(PreparedAgentRun.class),
                org.mockito.ArgumentMatchers.any(SupportAgentResult.class)
        )).thenReturn(40001L);
        SubmittedRun submitted = submitRun("成功消息");

        submitted.execution().run();

        assertThat(eventNames(submitted.emitter())).containsExactly(
                "run.started",
                "tool.started",
                "tool.completed",
                "message.delta",
                "message.completed",
                "run.completed"
        );
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(persistenceService, conversationRunLock);
        order.verify(persistenceService).complete(
                org.mockito.ArgumentMatchers.eq(submitted.prepared()),
                org.mockito.ArgumentMatchers.any(SupportAgentResult.class));
        order.verify(conversationRunLock).release(CONVERSATION_ID, submitted.prepared().runId());
        verify(heartbeat).cancel(false);
        verify(submitted.deadline()).cancel(false);
    }

    @Test
    void shouldLetCommittedCompletionPublishTerminalEventsWhenLateDeadlineLosesDatabaseRace() {
        when(supportAgent.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SupportAgentResult("公开答复", 1, 0, "support-v1"));
        SubmittedRun submitted = submitRun("完成提交与超时竞争");
        when(persistenceService.fail(submitted.prepared(), "RUN_TIMEOUT")).thenReturn(false);
        when(persistenceService.complete(
                org.mockito.ArgumentMatchers.eq(submitted.prepared()),
                org.mockito.ArgumentMatchers.any(SupportAgentResult.class)
        )).thenAnswer(invocation -> {
            submitted.deadlineAction().run();
            return 40002L;
        });

        submitted.execution().run();

        assertThat(eventNames(submitted.emitter())).containsExactly(
                "run.started",
                "message.completed",
                "run.completed"
        );
        verify(persistenceService).fail(submitted.prepared(), "RUN_TIMEOUT");
        verify(conversationRunLock, org.mockito.Mockito.times(1))
                .release(CONVERSATION_ID, submitted.prepared().runId());
    }

    @Test
    void shouldLetCommittedCompletionOwnCleanupWhenLateCancellationLosesDatabaseRace() {
        when(supportAgent.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SupportAgentResult("公开答复", 1, 0, "support-v1"));
        SubmittedRun submitted = submitRun("完成提交与断连竞争");
        when(persistenceService.cancel(submitted.prepared())).thenReturn(false);
        when(persistenceService.complete(
                org.mockito.ArgumentMatchers.eq(submitted.prepared()),
                org.mockito.ArgumentMatchers.any(SupportAgentResult.class)
        )).thenAnswer(invocation -> {
            Runnable completionCallback = (Runnable) ReflectionTestUtils.getField(
                    submitted.emitter(), "completionCallback");
            assertThat(completionCallback).isNotNull();
            completionCallback.run();
            return 40003L;
        });

        submitted.execution().run();

        assertThat(eventNames(submitted.emitter())).containsExactly("run.started");
        verify(persistenceService).cancel(submitted.prepared());
        verify(conversationRunLock, org.mockito.Mockito.times(1))
                .release(CONVERSATION_ID, submitted.prepared().runId());
    }

    @Test
    void shouldNotPublishCompletedEventsWhenCompletionTransactionFails() {
        when(supportAgent.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SupportAgentResult("不会确认完成", 1, 0, "support-v1"));
        when(persistenceService.complete(
                org.mockito.ArgumentMatchers.any(PreparedAgentRun.class),
                org.mockito.ArgumentMatchers.any(SupportAgentResult.class)
        )).thenThrow(new IllegalStateException("database write failed"));
        when(persistenceService.fail(
                org.mockito.ArgumentMatchers.any(PreparedAgentRun.class),
                org.mockito.ArgumentMatchers.eq("INTERNAL_ERROR")
        )).thenReturn(true);
        SubmittedRun submitted = submitRun("持久化失败");

        submitted.execution().run();

        assertThat(eventNames(submitted.emitter())).containsExactly("run.started", "run.failed");
        assertThat(eventPayloads(submitted.emitter()))
                .anySatisfy(payload -> assertThat(payload.get("errorCode")).isEqualTo("INTERNAL_ERROR"));
        verify(persistenceService).fail(submitted.prepared(), "INTERNAL_ERROR");
        verify(conversationRunLock).release(CONVERSATION_ID, submitted.prepared().runId());
    }

    @Test
    void shouldMapAgentExecutionExceptionToStableRunFailure() {
        when(supportAgent.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new AgentExecutionException("RAG_UNAVAILABLE", "知识服务不可用", null));
        when(persistenceService.fail(
                org.mockito.ArgumentMatchers.any(PreparedAgentRun.class),
                org.mockito.ArgumentMatchers.eq("RAG_UNAVAILABLE")
        )).thenReturn(true);
        SubmittedRun submitted = submitRun("下游失败");

        submitted.execution().run();

        assertThat(eventNames(submitted.emitter())).containsExactly("run.started", "run.failed");
        verify(persistenceService).fail(submitted.prepared(), "RAG_UNAVAILABLE");
        verify(conversationRunLock).release(CONVERSATION_ID, submitted.prepared().runId());
        verify(submitted.deadline()).cancel(false);
    }

    @Test
    void shouldMapUnknownFailureAndRestorePreviousMdcContext() {
        when(supportAgent.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("unexpected internal detail"));
        when(persistenceService.fail(
                org.mockito.ArgumentMatchers.any(PreparedAgentRun.class),
                org.mockito.ArgumentMatchers.eq("INTERNAL_ERROR")
        )).thenReturn(true);
        SubmittedRun submitted = submitRun("未知失败");
        MDC.put("outer-mdc", "execution-sentinel");

        try {
            submitted.execution().run();
            assertThat(MDC.getCopyOfContextMap())
                    .containsExactlyEntriesOf(Map.of("outer-mdc", "execution-sentinel"));
        } finally {
            MDC.clear();
        }

        verify(persistenceService).fail(submitted.prepared(), "INTERNAL_ERROR");
        verify(conversationRunLock).release(CONVERSATION_ID, submitted.prepared().runId());
    }

    @Test
    void shouldCloseSessionAndReleaseResourcesWhenFailureCannotBePersisted() {
        ScheduledFuture<?> heartbeat = org.mockito.Mockito.mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(heartbeat).when(heartbeatScheduler).scheduleWithFixedDelay(
                org.mockito.ArgumentMatchers.any(Runnable.class),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(TimeUnit.class));
        when(supportAgent.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new AgentExecutionException("RAG_UNAVAILABLE", "知识服务不可用", null));
        when(persistenceService.fail(
                org.mockito.ArgumentMatchers.any(PreparedAgentRun.class),
                org.mockito.ArgumentMatchers.eq("RAG_UNAVAILABLE")
        )).thenThrow(new IllegalStateException("database unavailable"));
        SubmittedRun submitted = submitRun("失败终态无法持久化");

        assertThatCode(submitted.execution()::run).doesNotThrowAnyException();

        verify(heartbeat).cancel(false);
        verify(submitted.deadline()).cancel(false);
        verify(conversationRunLock).release(CONVERSATION_ID, submitted.prepared().runId());
        assertThat(ReflectionTestUtils.getField(submitted.emitter(), "complete")).isEqualTo(true);
    }

    @Test
    void shouldCancelRunAndExecutionWhenClientCompletesConnection() {
        when(persistenceService.cancel(org.mockito.ArgumentMatchers.any(PreparedAgentRun.class))).thenReturn(true);
        SubmittedRun submitted = submitRun("客户端断开");
        AtomicReference<String> releaseTraceId = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            releaseTraceId.set(MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY));
            return null;
        }).when(conversationRunLock).release(CONVERSATION_ID, submitted.prepared().runId());

        Runnable completionCallback = (Runnable) ReflectionTestUtils.getField(
                submitted.emitter(), "completionCallback");
        assertThat(completionCallback).isNotNull();
        MDC.put("outer-mdc", "cancel-sentinel");
        try {
            completionCallback.run();
            assertThat(MDC.getCopyOfContextMap())
                    .containsExactlyEntriesOf(Map.of("outer-mdc", "cancel-sentinel"));
        } finally {
            MDC.clear();
        }

        verify(submitted.executionFuture()).cancel(true);
        verify(persistenceService).cancel(submitted.prepared());
        verify(conversationRunLock).release(CONVERSATION_ID, submitted.prepared().runId());
        verify(submitted.deadline()).cancel(false);
        assertThat(releaseTraceId).hasValue("trace-service-001");
    }

    @Test
    void shouldLogReleaseFailureWithoutThrowableOrSensitiveMessage() {
        when(persistenceService.cancel(org.mockito.ArgumentMatchers.any(PreparedAgentRun.class))).thenReturn(true);
        SubmittedRun submitted = submitRun("释放锁失败日志");
        org.mockito.Mockito.doThrow(new IllegalStateException("sentinel-sensitive-release-message"))
                .when(conversationRunLock)
                .release(CONVERSATION_ID, submitted.prepared().runId());
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AgentService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Runnable completionCallback = (Runnable) ReflectionTestUtils.getField(
                submitted.emitter(), "completionCallback");
        assertThat(completionCallback).isNotNull();
        MDC.put("outer-mdc", "release-log-sentinel");

        try {
            assertThatCode(completionCallback::run).doesNotThrowAnyException();

            ILoggingEvent releaseFailure = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("event=agent_run_lock_release_failed"))
                    .findFirst()
                    .orElseThrow();
            assertThat(releaseFailure.getFormattedMessage())
                    .contains(
                            "traceId=trace-service-001",
                            "exceptionType=IllegalStateException"
                    )
                    .doesNotContain("sentinel-sensitive-release-message");
            assertThat(releaseFailure.getThrowableProxy()).isNull();
            assertThat(MDC.getCopyOfContextMap())
                    .containsExactlyEntriesOf(Map.of("outer-mdc", "release-log-sentinel"));
        } finally {
            MDC.clear();
            logger.detachAppender(appender);
        }
    }

    @Test
    void shouldReleaseResourcesWhenCancellationCannotBePersisted() {
        when(persistenceService.cancel(org.mockito.ArgumentMatchers.any(PreparedAgentRun.class)))
                .thenThrow(new IllegalStateException("database unavailable"));
        SubmittedRun submitted = submitRun("客户端断开且终态持久化失败");
        Runnable completionCallback = (Runnable) ReflectionTestUtils.getField(
                submitted.emitter(), "completionCallback");
        assertThat(completionCallback).isNotNull();

        assertThatCode(completionCallback::run).doesNotThrowAnyException();

        verify(submitted.executionFuture()).cancel(true);
        verify(submitted.deadline()).cancel(false);
        verify(conversationRunLock).release(CONVERSATION_ID, submitted.prepared().runId());
    }

    @Test
    void shouldPersistTransportFailureAndReleaseResources() throws Exception {
        when(persistenceService.fail(
                org.mockito.ArgumentMatchers.any(PreparedAgentRun.class),
                org.mockito.ArgumentMatchers.eq("INTERNAL_ERROR")
        )).thenReturn(true);
        SubmittedRun submitted = submitRun("传输失败");
        installFailingEmitterHandler(submitted.emitter());

        submitted.execution().run();

        verify(submitted.executionFuture()).cancel(true);
        verify(persistenceService).fail(submitted.prepared(), "INTERNAL_ERROR");
        verify(conversationRunLock).release(CONVERSATION_ID, submitted.prepared().runId());
        verify(submitted.deadline()).cancel(false);
    }

    @Test
    void shouldRouteSupportClassificationToSupportAgent() {
        when(intentRouter.route(anyString(), org.mockito.ArgumentMatchers.any(), anyString()))
                .thenReturn(IntentRouter.RouteDecision.agent("SUPPORT"));
        when(supportAgent.agentType()).thenReturn("SUPPORT");
        when(supportAgent.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SupportAgentResult("知识库答复", 1, 0, "support-v1"));
        when(persistenceService.complete(
                org.mockito.ArgumentMatchers.any(PreparedAgentRun.class),
                org.mockito.ArgumentMatchers.any(SupportAgentResult.class))).thenReturn(40040L);
        SubmittedRun submitted = submitRun("客服问题");

        submitted.execution().run();

        verify(persistenceService).setAgentType(3L, "SUPPORT");
        verify(supportAgent).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(dailyAgent, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(orderAgent, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(fallbackAgent, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldRouteDailyClassificationToDailyAgent() {
        when(intentRouter.route(anyString(), org.mockito.ArgumentMatchers.any(), anyString()))
                .thenReturn(IntentRouter.RouteDecision.agent("DAILY"));
        when(dailyAgent.agentType()).thenReturn("DAILY");
        when(dailyAgent.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SupportAgentResult("明天晴，20 度", 1, 0, "support-v1"));
        when(persistenceService.complete(
                org.mockito.ArgumentMatchers.any(PreparedAgentRun.class),
                org.mockito.ArgumentMatchers.any(SupportAgentResult.class))).thenReturn(40020L);
        SubmittedRun submitted = submitRun("明天天气");

        submitted.execution().run();

        assertThat(eventNames(submitted.emitter()))
                .containsExactly("run.started", "message.completed", "run.completed");
        verify(persistenceService).setAgentType(3L, "DAILY");
        verify(dailyAgent).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(supportAgent, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(orderAgent, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(fallbackAgent, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(conversationRunLock).release(CONVERSATION_ID, submitted.prepared().runId());
    }

    @Test
    void shouldRouteOrderClassificationToOrderAgent() {
        when(intentRouter.route(anyString(), org.mockito.ArgumentMatchers.any(), anyString()))
                .thenReturn(IntentRouter.RouteDecision.agent("ORDER"));
        when(orderAgent.agentType()).thenReturn("ORDER");
        when(orderAgent.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SupportAgentResult("当前版本暂不支持在线下单。", 1, 0, "support-v1"));
        when(persistenceService.complete(
                org.mockito.ArgumentMatchers.any(PreparedAgentRun.class),
                org.mockito.ArgumentMatchers.any(SupportAgentResult.class))).thenReturn(40030L);
        SubmittedRun submitted = submitRun("我要打车");

        submitted.execution().run();

        verify(persistenceService).setAgentType(3L, "ORDER");
        verify(orderAgent).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(supportAgent, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(dailyAgent, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(fallbackAgent, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldRouteOtherClassificationToFallbackAgent() {
        when(intentRouter.route(anyString(), org.mockito.ArgumentMatchers.any(), anyString()))
                .thenReturn(IntentRouter.RouteDecision.agent("OTHER"));
        when(fallbackAgent.agentType()).thenReturn("FALLBACK");
        when(fallbackAgent.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SupportAgentResult("我专注出行服务。", 1, 0, "support-v1"));
        when(persistenceService.complete(
                org.mockito.ArgumentMatchers.any(PreparedAgentRun.class),
                org.mockito.ArgumentMatchers.any(SupportAgentResult.class))).thenReturn(40050L);
        SubmittedRun submitted = submitRun("讲个笑话");

        submitted.execution().run();

        assertThat(eventNames(submitted.emitter()))
                .containsExactly("run.started", "message.completed", "run.completed");
        verify(persistenceService).setAgentType(3L, "FALLBACK");
        verify(fallbackAgent).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(supportAgent, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(dailyAgent, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(orderAgent, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(conversationRunLock).release(CONVERSATION_ID, submitted.prepared().runId());
    }

    @Test
    void shouldEmitNotifyAndRouteOrderAgentWhenOrderSticky() {
        when(intentRouter.route(anyString(), org.mockito.ArgumentMatchers.any(), anyString()))
                .thenReturn(IntentRouter.RouteDecision.orderSticky("当前处于下单流程，如果您想换个话题，请新建对话。"));
        when(orderAgent.agentType()).thenReturn("ORDER");
        when(orderAgent.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SupportAgentResult("好的，先不下单了。", 1, 0, "support-v1"));
        when(persistenceService.complete(
                org.mockito.ArgumentMatchers.any(PreparedAgentRun.class),
                org.mockito.ArgumentMatchers.any(SupportAgentResult.class))).thenReturn(40060L);
        SubmittedRun submitted = submitRun("换个话题");

        submitted.execution().run();

        assertThat(eventNames(submitted.emitter()))
                .containsExactly("run.started", "notify", "message.completed", "run.completed");
        verify(persistenceService).setAgentType(3L, "ORDER");
        assertThat(eventPayloads(submitted.emitter()))
                .filteredOn(payload -> payload.get("content") != null)
                .singleElement()
                .satisfies(payload -> assertThat(payload.get("content"))
                        .isEqualTo("当前处于下单流程，如果您想换个话题，请新建对话。"));
        verify(orderAgent).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(supportAgent, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(dailyAgent, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(fallbackAgent, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(conversationRunLock).release(CONVERSATION_ID, submitted.prepared().runId());
    }

    @Test
    void shouldCompleteWithoutPersistenceWhenRouteFailure() {
        when(intentRouter.route(anyString(), org.mockito.ArgumentMatchers.any(), anyString()))
                .thenReturn(IntentRouter.RouteDecision.failure("路由失败，请换种方式问问题。"));
        when(persistenceService.completeWithoutMessage(org.mockito.ArgumentMatchers.any(PreparedAgentRun.class)))
                .thenReturn(true);
        SubmittedRun submitted = submitRun("分类失败");

        submitted.execution().run();

        assertThat(eventNames(submitted.emitter()))
                .containsExactly("run.started", "message.delta", "run.completed");
        assertThat(eventPayloads(submitted.emitter()))
                .filteredOn(payload -> payload.get("content") != null)
                .singleElement()
                .satisfies(payload -> assertThat(payload.get("content")).isEqualTo("路由失败，请换种方式问问题。"));
        verify(persistenceService).completeWithoutMessage(submitted.prepared());
        verify(persistenceService, never()).complete(
                org.mockito.ArgumentMatchers.any(PreparedAgentRun.class),
                org.mockito.ArgumentMatchers.any(SupportAgentResult.class));
        verify(supportAgent, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(dailyAgent, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(orderAgent, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(fallbackAgent, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(conversationRunLock).release(CONVERSATION_ID, submitted.prepared().runId());
    }

    @Test
    void shouldFailRunWhenRouteFailureCannotBePersisted() {
        when(intentRouter.route(anyString(), org.mockito.ArgumentMatchers.any(), anyString()))
                .thenReturn(IntentRouter.RouteDecision.failure("路由失败，请换种方式问问题。"));
        when(persistenceService.completeWithoutMessage(org.mockito.ArgumentMatchers.any(PreparedAgentRun.class)))
                .thenThrow(new IllegalStateException("database down"));
        when(persistenceService.fail(
                org.mockito.ArgumentMatchers.any(PreparedAgentRun.class),
                org.mockito.ArgumentMatchers.eq("INTERNAL_ERROR"))).thenReturn(true);
        SubmittedRun submitted = submitRun("失败无法落库");

        submitted.execution().run();

        assertThat(eventNames(submitted.emitter())).containsExactly("run.started", "run.failed");
        verify(persistenceService).fail(submitted.prepared(), "INTERNAL_ERROR");
        verify(conversationRunLock).release(CONVERSATION_ID, submitted.prepared().runId());
    }

    @Test
    void shouldFailRunWhenRouteReturnsUnknownAgentType() {
        when(intentRouter.route(anyString(), org.mockito.ArgumentMatchers.any(), anyString()))
                .thenReturn(IntentRouter.RouteDecision.agent("UNKNOWN"));
        when(persistenceService.fail(
                org.mockito.ArgumentMatchers.any(PreparedAgentRun.class),
                org.mockito.ArgumentMatchers.eq("UNKNOWN_AGENT_TYPE"))).thenReturn(true);
        SubmittedRun submitted = submitRun("未知类型");

        submitted.execution().run();

        assertThat(eventNames(submitted.emitter())).containsExactly("run.started", "run.failed");
        verify(persistenceService).fail(submitted.prepared(), "UNKNOWN_AGENT_TYPE");
        verify(conversationRunLock).release(CONVERSATION_ID, submitted.prepared().runId());
    }

    @Test
    void shouldRejectResumeWithoutPendingConfirmation() {
        when(orderStateService.isBreak(CONVERSATION_ID)).thenReturn(false);

        assertThatThrownBy(() -> agentService.resume(
                USER_ID, "Bearer private", "trace-resume-001", CONVERSATION_ID,
                new SendMessageRequest(UUID.randomUUID(), "确认下单")
        )).isInstanceOfSatisfying(AgentApiException.class, exception -> {
            assertThat(exception.getStatus().value()).isEqualTo(400);
            assertThat(exception.getCode()).isEqualTo("ORDER_NOT_IN_CONFIRMATION");
            assertThat(exception.getMessage()).isEqualTo("当前没有待确认的订单");
        });

        verify(orderStateService, never()).setBreak(CONVERSATION_ID, false);
        verify(persistenceService, never()).prepare(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(agentRunExecutor, never()).submit(org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    void shouldKeepBreakWhenResumePreparationFails() {
        when(orderStateService.isBreak(CONVERSATION_ID)).thenReturn(true);
        when(conversationRunLock.tryAcquire(
                org.mockito.ArgumentMatchers.eq(CONVERSATION_ID),
                anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class)
        )).thenReturn(true);
        when(persistenceService.findExistingRunId(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(CONVERSATION_ID),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        when(persistenceService.prepare(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("db unavailable"));

        assertThatThrownBy(() -> agentService.resume(
                USER_ID, "Bearer private", "trace-resume-003", CONVERSATION_ID,
                new SendMessageRequest(UUID.randomUUID(), "确认下单")
        )).isInstanceOf(IllegalStateException.class);

        verify(orderStateService, never()).setBreak(CONVERSATION_ID, false);
        verify(agentRunExecutor, never()).submit(org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    void shouldResumeBreakByInjectingConfirmFeedbackIntoOrderAgentHistory() {
        when(orderStateService.isBreak(CONVERSATION_ID)).thenReturn(true);
        when(orderAgent.agentType()).thenReturn("ORDER");
        when(orderAgent.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SupportAgentResult("订单已创建，行程与费用见消息。", 1, 0, "support-v1"));
        when(persistenceService.complete(
                org.mockito.ArgumentMatchers.any(PreparedAgentRun.class),
                org.mockito.ArgumentMatchers.any(SupportAgentResult.class))).thenReturn(40070L);
        SubmittedRun submitted = submitResumeRun("确认下单");

        submitted.execution().run();

        verify(orderStateService).setBreak(CONVERSATION_ID, false);
        verify(intentRouter, never()).route(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
        verify(persistenceService).setAgentType(3L, "ORDER");
        ArgumentCaptor<AgentExecutionContext> contextCaptor =
                ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(orderAgent).execute(contextCaptor.capture(), org.mockito.ArgumentMatchers.any());
        assertThat(contextCaptor.getValue().history()).containsExactly(
                ModelTurn.user("从国贸到机场"),
                ModelTurn.assistant("", List.of(new ModelToolCall("confirmOrder", "confirmOrder", "{}"))),
                ModelTurn.tool(List.of(new ModelToolResult("confirmOrder", "confirmOrder", "确认下单"))));
        verify(supportAgent, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(dailyAgent, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(fallbackAgent, never()).execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(eventNames(submitted.emitter()))
                .containsExactly("run.started", "message.completed", "run.completed");
        verify(conversationRunLock).release(CONVERSATION_ID, submitted.prepared().runId());
    }

    @Test
    void shouldRejectSecondResumeAfterBreakIsCleared() {
        when(orderStateService.isBreak(CONVERSATION_ID)).thenReturn(true, false);
        when(orderAgent.agentType()).thenReturn("ORDER");
        when(orderAgent.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SupportAgentResult("订单已创建，行程与费用见消息。", 1, 0, "support-v1"));
        when(persistenceService.complete(
                org.mockito.ArgumentMatchers.any(PreparedAgentRun.class),
                org.mockito.ArgumentMatchers.any(SupportAgentResult.class))).thenReturn(40080L);
        SubmittedRun submitted = submitResumeRun("确认下单");
        submitted.execution().run();

        assertThatThrownBy(() -> agentService.resume(
                USER_ID, "Bearer private", "trace-resume-002", CONVERSATION_ID,
                new SendMessageRequest(UUID.randomUUID(), "确认下单")
        )).isInstanceOfSatisfying(AgentApiException.class, exception -> {
            assertThat(exception.getStatus().value()).isEqualTo(400);
            assertThat(exception.getCode()).isEqualTo("ORDER_NOT_IN_CONFIRMATION");
        });
    }

    private SubmittedRun submitRun(String content) {
        UUID clientMessageId = UUID.randomUUID();
        when(persistenceService.findExistingRunId(USER_ID, CONVERSATION_ID, clientMessageId))
                .thenReturn(Optional.empty());
        when(conversationRunLock.tryAcquire(
                org.mockito.ArgumentMatchers.eq(CONVERSATION_ID),
                anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class)
        )).thenReturn(true);
        AtomicReference<PreparedAgentRun> prepared = new AtomicReference<>();
        when(persistenceService.prepare(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(CONVERSATION_ID),
                org.mockito.ArgumentMatchers.eq(clientMessageId),
                org.mockito.ArgumentMatchers.eq(content),
                anyString()
        )).thenAnswer(invocation -> {
            PreparedAgentRun value = new PreparedAgentRun(
                    1L,
                    2L,
                    3L,
                    invocation.getArgument(4),
                    content,
                    1L
            );
            prepared.set(value);
            return value;
        });
        ArgumentCaptor<Runnable> execution = ArgumentCaptor.forClass(Runnable.class);
        Future<?> executionFuture = org.mockito.Mockito.mock(Future.class);
        org.mockito.Mockito.doReturn(executionFuture).when(agentRunExecutor).submit(execution.capture());
        ScheduledFuture<?> deadline = org.mockito.Mockito.mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> deadlineAction = ArgumentCaptor.forClass(Runnable.class);
        org.mockito.Mockito.doReturn(deadline).when(heartbeatScheduler).schedule(
                deadlineAction.capture(),
                org.mockito.ArgumentMatchers.eq(60_000L),
                org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS));

        SseEmitter emitter = agentService.sendMessage(
                USER_ID,
                "Bearer private",
                "trace-service-001",
                CONVERSATION_ID,
                new SendMessageRequest(clientMessageId, content)
        );
        return new SubmittedRun(
                prepared.get(), emitter, execution.getValue(), executionFuture, deadline, deadlineAction.getValue());
    }

    private SubmittedRun submitResumeRun(String content) {
        UUID clientMessageId = UUID.randomUUID();
        when(orderStateService.getLastConfirmToolCallId(CONVERSATION_ID)).thenReturn("confirmOrder");
        when(persistenceService.findExistingRunId(USER_ID, CONVERSATION_ID, clientMessageId))
                .thenReturn(Optional.empty());
        when(conversationRunLock.tryAcquire(
                org.mockito.ArgumentMatchers.eq(CONVERSATION_ID),
                anyString(),
                org.mockito.ArgumentMatchers.any(Duration.class)
        )).thenReturn(true);
        AtomicReference<PreparedAgentRun> prepared = new AtomicReference<>();
        when(persistenceService.prepare(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(CONVERSATION_ID),
                org.mockito.ArgumentMatchers.eq(clientMessageId),
                org.mockito.ArgumentMatchers.eq(content),
                anyString()
        )).thenAnswer(invocation -> {
            PreparedAgentRun value = new PreparedAgentRun(
                    1L,
                    2L,
                    3L,
                    invocation.getArgument(4),
                    content,
                    1L
            );
            prepared.set(value);
            return value;
        });
        when(persistenceService.findHistoryBefore(1L, 1L, 30))
                .thenReturn(List.of(ModelTurn.user("从国贸到机场")));
        ArgumentCaptor<Runnable> execution = ArgumentCaptor.forClass(Runnable.class);
        Future<?> executionFuture = org.mockito.Mockito.mock(Future.class);
        org.mockito.Mockito.doReturn(executionFuture).when(agentRunExecutor).submit(execution.capture());
        ScheduledFuture<?> deadline = org.mockito.Mockito.mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> deadlineAction = ArgumentCaptor.forClass(Runnable.class);
        org.mockito.Mockito.doReturn(deadline).when(heartbeatScheduler).schedule(
                deadlineAction.capture(),
                org.mockito.ArgumentMatchers.eq(60_000L),
                org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS));

        SseEmitter emitter = agentService.resume(
                USER_ID,
                "Bearer private",
                "trace-resume-001",
                CONVERSATION_ID,
                new SendMessageRequest(clientMessageId, content)
        );
        return new SubmittedRun(
                prepared.get(), emitter, execution.getValue(), executionFuture, deadline, deadlineAction.getValue());
    }

    @SuppressWarnings("unchecked")
    private List<String> eventNames(SseEmitter emitter) {
        Set<ResponseBodyEmitter.DataWithMediaType> earlyAttempts =
                (Set<ResponseBodyEmitter.DataWithMediaType>) ReflectionTestUtils.getField(
                        emitter, "earlySendAttempts");
        assertThat(earlyAttempts).isNotNull();
        return earlyAttempts.stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .map(String::valueOf)
                .filter(value -> value.contains("event:"))
                .map(value -> value.substring(value.indexOf("event:") + "event:".length())
                        .split("\\Rdata:")[0].trim())
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> eventPayloads(SseEmitter emitter) {
        Set<ResponseBodyEmitter.DataWithMediaType> earlyAttempts =
                (Set<ResponseBodyEmitter.DataWithMediaType>) ReflectionTestUtils.getField(
                        emitter, "earlySendAttempts");
        assertThat(earlyAttempts).isNotNull();
        return earlyAttempts.stream()
                .map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(Map.class::isInstance)
                .map(value -> (Map<String, Object>) value)
                .toList();
    }

    private void installFailingEmitterHandler(SseEmitter emitter) throws Exception {
        Class<?> handlerType = Class.forName(
                "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter$Handler");
        Object handler = Proxy.newProxyInstance(
                handlerType.getClassLoader(),
                new Class<?>[]{handlerType},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("send")) {
                        throw new IOException("broken pipe");
                    }
                    if (method.getName().equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (method.getName().equals("equals")) {
                        return proxy == arguments[0];
                    }
                    if (method.getName().equals("toString")) {
                        return "FailingSseHandler";
                    }
                    return null;
                });
        Method initialize = ResponseBodyEmitter.class.getDeclaredMethod("initialize", handlerType);
        initialize.setAccessible(true);
        initialize.invoke(emitter, handler);
    }

    private record SubmittedRun(
            PreparedAgentRun prepared,
            SseEmitter emitter,
            Runnable execution,
            Future<?> executionFuture,
            ScheduledFuture<?> deadline,
            Runnable deadlineAction
    ) {
    }
}
