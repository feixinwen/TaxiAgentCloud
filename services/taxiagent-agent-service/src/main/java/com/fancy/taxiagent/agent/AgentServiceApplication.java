package com.fancy.taxiagent.agent;

import com.fancy.taxiagent.agent.classify.ClassifierProperties;
import com.fancy.taxiagent.agent.config.AgentExecutionProperties;
import com.fancy.taxiagent.agent.config.AgentModelProperties;
import com.fancy.taxiagent.agent.qweather.QweatherProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Agent Service 应用入口。
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.fancy.taxiagent.agent.client")
@EnableConfigurationProperties({AgentExecutionProperties.class, AgentModelProperties.class, ClassifierProperties.class, QweatherProperties.class})
public class AgentServiceApplication {

    /**
     * 启动 Agent Service。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(AgentServiceApplication.class, args);
    }
}
