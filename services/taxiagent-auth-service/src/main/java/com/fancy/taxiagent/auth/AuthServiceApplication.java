package com.fancy.taxiagent.auth;

import com.fancy.taxiagent.auth.config.TokenProperties;
import com.fancy.taxiagent.auth.config.VerificationCodeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;

/**
 * TaxiAgent 认证服务启动入口。
 *
 * <p>当前已具备独立认证数据源、JWT 签发、服务身份、User Feign 客户端和全局 ID 基础能力；
 * 注册与登录业务接口按迁移计划继续分阶段实现。</p>
 */
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.fancy.taxiagent.auth.client")
@SpringBootApplication
public class AuthServiceApplication {

    /**
     * 启动认证服务 Spring Boot 应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }

    @Bean
    @ConfigurationProperties(prefix = "taxiagent.auth.verification-code")
    VerificationCodeProperties verificationCodeProperties() {
        return new VerificationCodeProperties();
    }

    @Bean
    @ConfigurationProperties(prefix = "taxiagent.auth.token")
    TokenProperties tokenProperties() {
        return new TokenProperties();
    }
}
