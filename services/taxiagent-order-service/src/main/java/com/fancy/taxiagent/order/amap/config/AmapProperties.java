package com.fancy.taxiagent.order.amap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 高德 WebService API 配置。
 */
@ConfigurationProperties(prefix = "taxiagent.amap")
public class AmapProperties {

    /** WebService API base url */
    private String url = "https://restapi.amap.com/v3";

    /** WebService Key */
    private String key;

    /** 连接/读取超时（毫秒） */
    private int timeout = 5000;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }
}
