package com.fancy.taxiagent.user.rocketmq;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RocketMQ 客户端连接配置。
 *
 * <p>通过 {@code taxiagent.rocketmq.*} 前缀绑定；{@code enabled=false} 时 producer 不启动，
 * 便于无 MQ 环境的测试上下文正常加载。</p>
 */
@ConfigurationProperties(prefix = "taxiagent.rocketmq")
public class RocketMqProperties {

    private String nameServer = "127.0.0.1:9876";

    private String producerGroup = "taxiagent-user-producer";

    private boolean enabled = true;

    public String getNameServer() {
        return nameServer;
    }

    public void setNameServer(String nameServer) {
        this.nameServer = nameServer;
    }

    public String getProducerGroup() {
        return producerGroup;
    }

    public void setProducerGroup(String producerGroup) {
        this.producerGroup = producerGroup;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
