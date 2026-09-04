package com.fancy.taxiagent.agent.stream;

import java.math.BigDecimal;

import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证单个 SSE 连接的事件编号、心跳与终止状态。
 */
class AgentSseSessionTest {

    @Test
    void shouldAssignStrictlyIncreasingEventIdsStartingAtOne() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            CapturingEmitter emitter = new CapturingEmitter();
            AgentSseSession session = new AgentSseSession(
                    emitter, scheduler, Duration.ofSeconds(15), "run-001");

            assertThat(session.publish(AgentSseEvent.runStarted("run-001", "conversation-001")).id()).isEqualTo(1L);
            assertThat(session.publish(AgentSseEvent.messageDelta("run-001", "您好")).id()).isEqualTo(2L);
            assertThat(session.publish(AgentSseEvent.runCompleted("run-001")).id()).isEqualTo(3L);
            assertThat(emitter.eventNames()).containsExactly("run.started", "message.delta", "run.completed");
            assertThat(emitter.payloads()).containsExactly(
                    Map.of("runId", "run-001", "conversationId", "conversation-001"),
                    Map.of("runId", "run-001", "content", "您好"),
                    Map.of("runId", "run-001")
            );
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void shouldPublishConfirmEvent() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            CapturingEmitter emitter = new CapturingEmitter();
            AgentSseSession session = new AgentSseSession(
                    emitter, scheduler, Duration.ofSeconds(15), "run-001");

            session.publish(AgentSseEvent.confirm("run-001", "{\"estPrice\":8}", null));

            assertThat(emitter.eventNames()).containsExactly("confirm");
            assertThat(emitter.payloads()).containsExactly(
                    Map.of("runId", "run-001", "content", "{\"estPrice\":8}"));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void shouldFlattenRouteDataIntoConfirmEvent() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            CapturingEmitter emitter = new CapturingEmitter();
            AgentSseSession session = new AgentSseSession(
                    emitter, scheduler, Duration.ofSeconds(15), "run-001");

            session.publish(AgentSseEvent.confirm("run-001", "{}", Map.of(
                    "traceId", "trace-1",
                    "estDistance", new BigDecimal("23.44"),
                    "startLat", 31.1979D,
                    "startLng", 121.3363D,
                    "endLat", 31.2304D,
                    "endLng", 121.4737D)));

            assertThat(emitter.eventNames()).containsExactly("confirm");
            assertThat(emitter.payloads().get(0))
                    .containsEntry("runId", "run-001")
                    .containsEntry("traceId", "trace-1")
                    .containsEntry("estDistance", new BigDecimal("23.44"))
                    .containsEntry("startLat", 31.1979D)
                    .containsEntry("endLng", 121.4737D);
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void shouldNotTreatNormalCompletionAsClientCancellation() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            AgentSseSession session = new AgentSseSession(
                    mock(SseEmitter.class), scheduler, Duration.ofSeconds(15), "run-001");

            session.complete();

            assertThat(session.isCancelled()).isFalse();
            assertThat(session.isTerminated()).isTrue();
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void shouldCancelBoundResourcesAndInvokeCallbackWhenTimeoutOccurs() {
        SseEmitter emitter = mock(SseEmitter.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Runnable> completion = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Consumer<Throwable>> error = ArgumentCaptor.forClass(Consumer.class);
        org.mockito.Mockito.doNothing().when(emitter).onTimeout(timeout.capture());
        org.mockito.Mockito.doNothing().when(emitter).onCompletion(completion.capture());
        org.mockito.Mockito.doNothing().when(emitter).onError(error.capture());
        Future<?> execution = mock(Future.class);
        ScheduledFuture<?> deadline = mock(ScheduledFuture.class);
        AtomicInteger cancelled = new AtomicInteger();
        AgentSseSession session = new AgentSseSession(emitter, scheduler, Duration.ofSeconds(15), "run-001");
        session.onCancelled(cancelled::incrementAndGet);
        session.bindExecutionTask(execution);
        session.bindDeadlineTask(deadline);

        timeout.getValue().run();

        assertThat(session.isCancelled()).isTrue();
        assertThat(cancelled.get()).isEqualTo(1);
        verify(execution).cancel(true);
        verify(deadline).cancel(false);
        completion.getValue().run();
        error.getValue().accept(new IOException("disconnect"));
        assertThat(cancelled.get()).isEqualTo(1);
    }

    @Test
    void shouldTreatUnexpectedEmitterCompletionAsClientCancellation() {
        SseEmitter emitter = mock(SseEmitter.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ArgumentCaptor<Runnable> completion = ArgumentCaptor.forClass(Runnable.class);
        org.mockito.Mockito.doNothing().when(emitter).onCompletion(completion.capture());
        AtomicInteger cancelled = new AtomicInteger();
        AgentSseSession session = new AgentSseSession(
                emitter, scheduler, Duration.ofSeconds(15), "run-001");
        session.onCancelled(cancelled::incrementAndGet);

        completion.getValue().run();

        assertThat(session.isCancelled()).isTrue();
        assertThat(cancelled.get()).isEqualTo(1);
    }

    @Test
    void shouldTreatEmitterErrorAsClientCancellation() {
        SseEmitter emitter = mock(SseEmitter.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ArgumentCaptor<Consumer<Throwable>> error = ArgumentCaptor.forClass(Consumer.class);
        org.mockito.Mockito.doNothing().when(emitter).onError(error.capture());
        AtomicInteger cancelled = new AtomicInteger();
        AgentSseSession session = new AgentSseSession(
                emitter, scheduler, Duration.ofSeconds(15), "run-001");
        session.onCancelled(cancelled::incrementAndGet);

        error.getValue().accept(new IOException("client disconnected"));

        assertThat(session.isCancelled()).isTrue();
        assertThat(cancelled.get()).isEqualTo(1);
    }

    @Test
    void shouldSendHeartbeatOnlyAfterConfiguredIdleInterval() {
        CapturingEmitter emitter = new CapturingEmitter();
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<Object> heartbeatFuture = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> heartbeat = ArgumentCaptor.forClass(Runnable.class);
        org.mockito.Mockito.doReturn(heartbeatFuture).when(scheduler).scheduleWithFixedDelay(
                heartbeat.capture(),
                org.mockito.ArgumentMatchers.eq(15_000L),
                org.mockito.ArgumentMatchers.eq(15_000L),
                org.mockito.ArgumentMatchers.eq(TimeUnit.MILLISECONDS)
        );
        AtomicLong now = new AtomicLong(1_000L);
        AgentSseSession session = new AgentSseSession(
                emitter,
                scheduler,
                Duration.ofSeconds(15),
                "run-001",
                now::get
        );
        session.startHeartbeat();

        heartbeat.getValue().run();
        assertThat(emitter.eventNames()).isEmpty();

        now.addAndGet(Duration.ofSeconds(15).toNanos());
        heartbeat.getValue().run();
        assertThat(emitter.eventNames()).containsExactly("heartbeat");

        session.publish(AgentSseEvent.messageDelta("run-001", "公开增量"));
        now.addAndGet(Duration.ofSeconds(14).toNanos());
        heartbeat.getValue().run();
        assertThat(emitter.eventNames()).containsExactly("heartbeat", "message.delta");

        now.addAndGet(Duration.ofSeconds(1).toNanos());
        heartbeat.getValue().run();
        assertThat(emitter.eventNames()).containsExactly("heartbeat", "message.delta", "heartbeat");
    }

    @Test
    void shouldSerializeConcurrentEmitterSendsAndAssignUniqueIds() throws Exception {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ConcurrencyDetectingEmitter emitter = new ConcurrencyDetectingEmitter();
        AgentSseSession session = new AgentSseSession(
                emitter, scheduler, Duration.ofSeconds(15), "run-001");
        List<Long> ids = java.util.Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> publishes = new ArrayList<>();
            LongStream.range(0, 32).forEach(index -> publishes.add(executor.submit(
                    (Runnable) () -> ids.add(session.publish(
                            AgentSseEvent.messageDelta("run-001", "delta-" + index)).id()))));
            for (Future<?> publish : publishes) {
                publish.get(10, TimeUnit.SECONDS);
            }
        }

        assertThat(emitter.maximumConcurrentSends()).isEqualTo(1);
        assertThat(ids).hasSize(32);
        Set<Long> expectedIds = LongStream.rangeClosed(1, 32).boxed().collect(Collectors.toSet());
        assertThat(Set.copyOf(ids)).isEqualTo(expectedIds);
    }

    @Test
    void shouldHandleIOExceptionAsTransportFailureAndCancelResources() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        Future<?> execution = mock(Future.class);
        ScheduledFuture<?> deadline = mock(ScheduledFuture.class);
        AtomicInteger transportFailures = new AtomicInteger();
        doThrow(new IOException("broken pipe"))
                .when(emitter).send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        AgentSseSession session = new AgentSseSession(
                emitter, scheduler, Duration.ofSeconds(15), "run-001");
        session.bindExecutionTask(execution);
        session.bindDeadlineTask(deadline);
        session.onTransportFailure(transportFailures::incrementAndGet);

        assertThatThrownBy(() -> session.publish(
                        AgentSseEvent.runStarted("run-001", "conversation-001")))
                .isInstanceOfSatisfying(AgentExecutionException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("INTERNAL_ERROR"));

        assertThat(transportFailures.get()).isEqualTo(1);
        assertThat(session.isTerminated()).isTrue();
        verify(execution).cancel(true);
        verify(deadline).cancel(false);
    }

    @Test
    void shouldHandleRuntimeExceptionAsTransportFailure() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        AtomicInteger transportFailures = new AtomicInteger();
        doThrow(new IllegalStateException("response closed"))
                .when(emitter).send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        AgentSseSession session = new AgentSseSession(
                emitter, scheduler, Duration.ofSeconds(15), "run-001");
        session.onTransportFailure(transportFailures::incrementAndGet);

        assertThatThrownBy(() -> session.publish(
                        AgentSseEvent.runStarted("run-001", "conversation-001")))
                .isInstanceOf(AgentExecutionException.class);

        assertThat(transportFailures.get()).isEqualTo(1);
        assertThat(session.isTerminated()).isTrue();
    }

    @Test
    void shouldCancelTaskBoundAfterTransportAlreadyFailed() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        doThrow(new IOException("early disconnect"))
                .when(emitter).send(org.mockito.ArgumentMatchers.any(SseEmitter.SseEventBuilder.class));
        AgentSseSession session = new AgentSseSession(
                emitter, scheduler, Duration.ofSeconds(15), "run-001");

        catchThrowable(() -> session.publish(
                AgentSseEvent.runStarted("run-001", "conversation-001")));
        Future<?> lateExecution = mock(Future.class);
        session.bindExecutionTask(lateExecution);

        verify(lateExecution).cancel(true);
    }

    @Test
    void shouldCancelHeartbeatAndDeadlineFuturesOnNormalCompletion() {
        SseEmitter emitter = mock(SseEmitter.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<Object> heartbeat = mock(ScheduledFuture.class);
        ScheduledFuture<?> deadline = mock(ScheduledFuture.class);
        org.mockito.Mockito.doReturn(heartbeat).when(scheduler).scheduleWithFixedDelay(
                org.mockito.ArgumentMatchers.any(Runnable.class),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(TimeUnit.class)
        );
        AgentSseSession session = new AgentSseSession(
                emitter, scheduler, Duration.ofSeconds(15), "run-001");
        session.bindDeadlineTask(deadline);
        session.startHeartbeat();

        session.complete();

        verify(heartbeat).cancel(false);
        verify(deadline).cancel(false);
        verify(emitter).complete();
    }

    @Test
    void shouldCancelHeartbeatCreatedWhileSessionTerminates() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<Object> heartbeat = mock(ScheduledFuture.class);
        CountDownLatch schedulingEntered = new CountDownLatch(1);
        CountDownLatch allowScheduleToReturn = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            schedulingEntered.countDown();
            if (!allowScheduleToReturn.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待终止竞态超时");
            }
            return heartbeat;
        }).when(scheduler).scheduleWithFixedDelay(
                org.mockito.ArgumentMatchers.any(Runnable.class),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(TimeUnit.class)
        );
        AgentSseSession session = new AgentSseSession(
                emitter, scheduler, Duration.ofSeconds(15), "run-race");

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> start = executor.submit(session::startHeartbeat);
            assertThat(schedulingEntered.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> terminate = executor.submit(session::complete);
            long waitUntil = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!session.isTerminated() && System.nanoTime() < waitUntil) {
                Thread.onSpinWait();
            }
            assertThat(session.isTerminated()).isTrue();
            allowScheduleToReturn.countDown();
            start.get(10, TimeUnit.SECONDS);
            terminate.get(10, TimeUnit.SECONDS);
        }

        verify(heartbeat).cancel(false);
    }

    private static final class CapturingEmitter extends SseEmitter {

        private final List<List<Object>> sends = new ArrayList<>();

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            sends.add(builder.build().stream().map(ResponseBodyEmitter.DataWithMediaType::getData).toList());
        }

        List<String> eventNames() {
            return sends.stream()
                    .map(send -> send.stream()
                            .map(String::valueOf)
                            .filter(value -> value.contains("event:"))
                            .map(value -> value.substring(value.indexOf("event:") + "event:".length())
                                    .split("\\Rdata:")[0].trim())
                            .findFirst()
                            .orElseThrow())
                    .toList();
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> payloads() {
            return sends.stream()
                    .map(send -> send.stream()
                            .filter(Map.class::isInstance)
                            .map(value -> (Map<String, Object>) value)
                            .findFirst()
                            .orElseThrow())
                    .toList();
        }
    }

    private static final class ConcurrencyDetectingEmitter extends SseEmitter {

        private final AtomicInteger activeSends = new AtomicInteger();
        private final AtomicInteger maximumConcurrentSends = new AtomicInteger();

        @Override
        public void send(SseEventBuilder builder) {
            int active = activeSends.incrementAndGet();
            maximumConcurrentSends.accumulateAndGet(active, Math::max);
            try {
                Thread.sleep(2L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("并发发送测试被中断", exception);
            } finally {
                activeSends.decrementAndGet();
            }
        }

        int maximumConcurrentSends() {
            return maximumConcurrentSends.get();
        }
    }

    @Test
    void messageCompletedShouldExposeMessageIdAsStringForJsSafeInteger() {
        long snowflake = 512359092993880065L;
        AgentSseEvent event = AgentSseEvent.messageCompleted("run-1", snowflake);
        assertThat(event.data().get("messageId")).isEqualTo(String.valueOf(snowflake));
        assertThat(event.data().get("messageId")).isInstanceOf(String.class);
    }
}
