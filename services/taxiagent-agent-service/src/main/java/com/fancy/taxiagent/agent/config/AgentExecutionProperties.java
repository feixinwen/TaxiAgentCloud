package com.fancy.taxiagent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Agent 单次运行的超时、锁和工具循环边界配置。
 */
@ConfigurationProperties(prefix = "taxiagent.agent.execution")
public class AgentExecutionProperties {

    private Duration runTimeout = Duration.ofSeconds(60);
    private Duration lockTtl = Duration.ofSeconds(90);
    private Duration sseTimeout = Duration.ofSeconds(65);
    private Duration heartbeatInterval = Duration.ofSeconds(15);
    private int maxModelRounds = 5;
    private int maxToolCalls = 8;

    public Duration getRunTimeout() {
        return runTimeout;
    }

    public void setRunTimeout(Duration runTimeout) {
        this.runTimeout = runTimeout;
    }

    public Duration getLockTtl() {
        return lockTtl;
    }

    public void setLockTtl(Duration lockTtl) {
        this.lockTtl = lockTtl;
    }

    public Duration getSseTimeout() {
        return sseTimeout;
    }

    public void setSseTimeout(Duration sseTimeout) {
        this.sseTimeout = sseTimeout;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public int getMaxModelRounds() {
        return maxModelRounds;
    }

    public void setMaxModelRounds(int maxModelRounds) {
        this.maxModelRounds = maxModelRounds;
    }

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(int maxToolCalls) {
        this.maxToolCalls = maxToolCalls;
    }
}
