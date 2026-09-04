package com.fancy.taxiagent.agent.config;

import feign.Logger;
import feign.Request;
import feign.Retryer;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * Agent Service 调用 order-service 时的 Feign 配置。
 *
 * <p>下单（createOrder）会同步完成高德路线规划、轨迹写入 MongoDB 与计价，
 * 实测耗时约 3 秒，可能超过通用 3 秒读超时（AgentFeignConfiguration），
 * 因此读超时放宽到 10 秒；连接超时与禁止重试、关闭日志等安全约束保持一致。
 * 禁止重试对非幂等的下单 POST 尤其重要——超时后订单可能已在 order-service 落库。</p>
 */
public class OrderFeignConfiguration {

    /**
     * 配置订单调用的连接和读取超时。
     *
     * @return 连接 3 秒、读取 10 秒的配置
     */
    @Bean
    public Request.Options orderFeignRequestOptions() {
        return new Request.Options(Duration.ofSeconds(3), Duration.ofSeconds(10), true);
    }

    /**
     * 禁用订单调用的 Feign 自动重试。
     *
     * @return 不自动重试的重试器
     */
    @Bean
    public Retryer orderFeignRetryer() {
        return Retryer.NEVER_RETRY;
    }

    /**
     * 禁用 Feign 请求和响应日志，避免泄露敏感载荷。
     *
     * @return 不记录 Feign 调用内容的日志级别
     */
    @Bean
    public Logger.Level orderFeignLoggerLevel() {
        return Logger.Level.NONE;
    }
}
