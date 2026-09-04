package com.fancy.taxiagent.agent.config;

import feign.Logger;
import feign.Request;
import feign.Retryer;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * Agent Service 调用下游服务时使用的 Feign 安全配置。
 */
public class AgentFeignConfiguration {

    /**
     * 配置 RAG 调用的连接和读取超时。
     *
     * @return 连接与读取超时均为三秒的配置
     */
    @Bean
    public Request.Options ragFeignRequestOptions() {
        return new Request.Options(Duration.ofSeconds(3), Duration.ofSeconds(3), true);
    }

    /**
     * 禁用 RAG 调用的 Feign 自动重试。
     *
     * @return 不自动重试的重试器
     */
    @Bean
    public Retryer ragFeignRetryer() {
        return Retryer.NEVER_RETRY;
    }

    /**
     * 禁用 Feign 请求和响应日志，避免泄露敏感载荷。
     *
     * @return 不记录 Feign 调用内容的日志级别
     */
    @Bean
    public Logger.Level ragFeignLoggerLevel() {
        return Logger.Level.NONE;
    }
}
