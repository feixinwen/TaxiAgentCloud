package com.fancy.taxiagent.auth.rocketmq;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 客户端连接配置（Auth 侧消费端）。
 *
 * <p>通过 {@code taxiagent.rocketmq.*} 前缀绑定；{@code enabled=false} 时消费端不启动，
 * 便于无 MQ 环境的测试上下文正常加载。{@code @Component} 自注册，
 * 无需在启动类上额外开启配置属性扫描。</p>
 */
@Component
@ConfigurationProperties(prefix = "taxiagent.rocketmq")
public class RocketMqProperties {

    private String nameServer = "127.0.0.1:9876";

    private String consumerGroup = "taxiagent-auth-user-events";

    private String topic = "user-events";

    private boolean enabled = true;

    public String getNameServer() {
        return nameServer;
    }

    public void setNameServer(String nameServer) {
        this.nameServer = nameServer;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
