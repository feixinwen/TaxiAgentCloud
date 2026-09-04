package com.fancy.taxiagent.agent.qweather;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 和风天气客户端配置。
 */
@ConfigurationProperties(prefix = "qweather")
public class QweatherProperties {

    /** 服务基础地址。 */
    private String url = "https://devapi.qweather.com";

    /** 和风 API Key（query 参数）。 */
    private String key = "";

    /** 和风 Token（X-QW-Token 头，可选）。 */
    private String token = "";

    /** 单次请求超时秒数。 */
    private int timeoutSeconds = 5;

    /**
     * 服务基础地址。
     *
     * @return 基础地址
     */
    public String getUrl() {
        return url;
    }

    /**
     * 设置服务基础地址。
     *
     * @param url 基础地址
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * 和风 API Key（query 参数，缺失时天气工具降级为"天气服务暂不可用"）。
     *
     * @return API Key
     */
    public String getKey() {
        return key;
    }

    /**
     * 设置和风 API Key。
     *
     * @param key API Key
     */
    public void setKey(String key) {
        this.key = key;
    }

    /**
     * 和风 Token（X-QW-Token 头，可选）。
     *
     * @return Token
     */
    public String getToken() {
        return token;
    }

    /**
     * 设置和风 Token。
     *
     * @param token Token
     */
    public void setToken(String token) {
        this.token = token;
    }

    /**
     * 单次请求超时秒数。
     *
     * @return 超时秒数
     */
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * 设置单次请求超时秒数。
     *
     * @param timeoutSeconds 超时秒数
     */
    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
