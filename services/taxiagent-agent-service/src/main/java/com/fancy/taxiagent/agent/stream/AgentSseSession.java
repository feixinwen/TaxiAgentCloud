package com.fancy.taxiagent.agent.stream;

import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 管理单个 SSE 连接的编号、心跳、取消状态及其任务资源。
 */
public class AgentSseSession {

    private final SseEmitter emitter;
    private final ScheduledExecutorService heartbeatScheduler;
    private final Duration heartbeatInterval;
    private final String runId;
    private final AtomicLong eventId = new AtomicLong();
    private final AtomicLong lastActivityNanos;
    private final LongSupplier nanoTime;
    private final AtomicBoolean terminated = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean serverCompleted = new AtomicBoolean();
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile Future<?> executionTask;
    private volatile ScheduledFuture<?> deadlineTask;
    private volatile Runnable cancellationHandler = () -> { };
    private volatile Runnable transportFailureHandler = () -> { };

    /**
     * 创建单个 SSE 会话。
     *
     * @param emitter Spring SSE 发射器
     * @param heartbeatScheduler 受 Spring 管理的心跳调度器
     * @param heartbeatInterval 空闲心跳间隔
     * @param runId 当前连接绑定的 Run 标识
     */
    public AgentSseSession(
            SseEmitter emitter,
            ScheduledExecutorService heartbeatScheduler,
            Duration heartbeatInterval,
            String runId
    ) {
        this(emitter, heartbeatScheduler, heartbeatInterval, runId, System::nanoTime);
    }

    AgentSseSession(
            SseEmitter emitter,
            ScheduledExecutorService heartbeatScheduler,
            Duration heartbeatInterval,
            String runId,
            LongSupplier nanoTime
    ) {
        this.emitter = emitter;
        this.heartbeatScheduler = heartbeatScheduler;
        this.heartbeatInterval = heartbeatInterval;
        this.runId = runId;
        this.nanoTime = nanoTime;
        this.lastActivityNanos = new AtomicLong(nanoTime.getAsLong());
        emitter.onCompletion(this::onCompletion);
        emitter.onTimeout(this::cancel);
        emitter.onError(ignored -> cancel());
    }

    /**
     * 发布单个安全公开事件，并为该连接分配递增序号。
     *
     * @param event 未编号的公开事件
     * @return 已编号的公开事件
     */
    public synchronized AgentSseEvent publish(AgentSseEvent event) {
        if (terminated.get()) {
            throw new AgentExecutionException("SSE_UNAVAILABLE", "SSE 连接已终止", null);
        }
        AgentSseEvent numbered = event.withId(eventId.incrementAndGet());
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(numbered.id()))
                    .name(numbered.name())
                    .data(numbered.data()));
            lastActivityNanos.set(nanoTime.getAsLong());
            return numbered;
        } catch (IOException | RuntimeException exception) {
            handleTransportFailure();
            throw new AgentExecutionException("INTERNAL_ERROR", "SSE 事件发送失败", exception);
        }
    }

    /**
     * 启动仅在连接空闲时发送的定时心跳。
     */
    public synchronized void startHeartbeat() {
        if (terminated.get() || heartbeatTask != null) {
            return;
        }
        heartbeatTask = heartbeatScheduler.scheduleWithFixedDelay(this::sendHeartbeatWhenIdle,
                heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * 绑定异步执行任务，以便客户端断开时可中断任务。
     *
     * @param task 运行中的 Agent 任务
     */
    public void bindExecutionTask(Future<?> task) {
        executionTask = task;
        if (terminated.get()) {
            task.cancel(true);
        }
    }

    /**
     * 绑定 Run 超时任务，以便任一终态均可取消该任务。
     *
     * @param task Run 超时调度任务
     */
    public void bindDeadlineTask(ScheduledFuture<?> task) {
        deadlineTask = task;
        if (terminated.get()) {
            task.cancel(false);
        }
    }

    /**
     * 注册客户端断开或超时后的取消回调。
     *
     * @param handler 取消回调
     */
    public void onCancelled(Runnable handler) {
        cancellationHandler = handler == null ? () -> { } : handler;
    }

    /**
     * 注册 SSE 写入失败后的回调。
     *
     * @param handler 传输失败回调
     */
    public void onTransportFailure(Runnable handler) {
        transportFailureHandler = handler == null ? () -> { } : handler;
    }

    /**
     * 正常关闭服务端 SSE，会保留“非客户端取消”的终止原因。
     */
    public void complete() {
        if (terminated.compareAndSet(false, true)) {
            serverCompleted.set(true);
            stopHeartbeat();
            emitter.complete();
        }
    }

    /**
     * 标记客户端取消并中断执行任务。
     */
    public void cancel() {
        if (terminated.compareAndSet(false, true)) {
            cancelled.set(true);
            stopHeartbeat();
            Future<?> task = executionTask;
            if (task != null) {
                task.cancel(true);
            }
            cancellationHandler.run();
        }
    }

    /**
     * 返回连接是否因客户端取消而终止。
     *
     * @return 是否已取消
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 返回连接是否已终止。
     *
     * @return 是否已终止
     */
    public boolean isTerminated() {
        return terminated.get();
    }

    /**
     * 返回本连接绑定的对外 Run 标识。
     *
     * @return Run 标识
     */
    public String runId() {
        return runId;
    }

    private synchronized void sendHeartbeatWhenIdle() {
        if (terminated.get()) {
            return;
        }
        long elapsedNanos = nanoTime.getAsLong() - lastActivityNanos.get();
        if (elapsedNanos >= heartbeatInterval.toNanos()) {
            try {
                publish(AgentSseEvent.heartbeat(runId));
            } catch (AgentExecutionException ignored) {
                // 已由 publish 触发传输失败收尾，调度线程无需暴露异常。
            }
        }
    }

    private void onCompletion() {
        if (!serverCompleted.get()) {
            cancel();
        }
    }

    private void handleTransportFailure() {
        if (terminated.compareAndSet(false, true)) {
            stopHeartbeat();
            Future<?> task = executionTask;
            if (task != null) {
                task.cancel(true);
            }
            transportFailureHandler.run();
        }
    }

    private synchronized void stopHeartbeat() {
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
        }
        ScheduledFuture<?> deadline = deadlineTask;
        if (deadline != null) {
            deadline.cancel(false);
        }
    }
}
