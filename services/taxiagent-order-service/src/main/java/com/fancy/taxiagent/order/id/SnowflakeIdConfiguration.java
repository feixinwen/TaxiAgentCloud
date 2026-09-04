package com.fancy.taxiagent.order.id;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Order Service 的业务 ID 生成器装配配置。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SnowflakeIdProperties.class)
public class SnowflakeIdConfiguration {

    @Bean
    IdGenerator orderIdGenerator(SnowflakeIdProperties properties) {
        return new SnowflakeIdGenerator(properties.getWorkerId(), properties.getDatacenterId());
    }
}
