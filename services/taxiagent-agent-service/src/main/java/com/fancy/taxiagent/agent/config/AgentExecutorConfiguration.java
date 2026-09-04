package com.fancy.taxiagent.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 配置受 Spring 生命周期管理的 Agent 执行与心跳线程资源。
 */
@Configuration
public class AgentExecutorConfiguration {

    /**
     * 创建每个任务使用虚拟线程的 Agent 执行器。
     *
     * @return 受 Spring 关闭管理的执行器
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService agentRunExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 创建用于 SSE 心跳的受控调度器。
     *
     * @return 受 Spring 关闭管理的心跳调度器
     */
    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService agentHeartbeatScheduler() {
        return Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                .name("agent-sse-heartbeat-", 0)
                .factory());
    }
}
