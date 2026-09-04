package com.fancy.taxiagent.auth.id;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auth Service 的业务 ID 生成器装配配置。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SnowflakeIdProperties.class)
public class SnowflakeIdConfiguration {

    @Bean
    IdGenerator userIdGenerator(SnowflakeIdProperties properties) {
        return new SnowflakeIdGenerator(properties.getWorkerId(), properties.getDatacenterId());
    }
}
