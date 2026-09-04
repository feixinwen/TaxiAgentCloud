package com.fancy.taxiagent.order.id;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Order Service 雪花 ID 节点配置。
 *
 * <p>同一数据中心内每个运行副本必须使用不同 workerId；不同集群或地域应使用不同 datacenterId。</p>
 */
@ConfigurationProperties(prefix = "taxiagent.order.id")
public class SnowflakeIdProperties {

    private int workerId;

    private int datacenterId;

    public int getWorkerId() {
        return workerId;
    }

    public void setWorkerId(int workerId) {
        this.workerId = workerId;
    }

    public int getDatacenterId() {
        return datacenterId;
    }

    public void setDatacenterId(int datacenterId) {
        this.datacenterId = datacenterId;
    }
}
