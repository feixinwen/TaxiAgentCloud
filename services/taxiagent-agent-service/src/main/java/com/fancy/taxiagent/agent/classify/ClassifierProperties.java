package com.fancy.taxiagent.agent.classify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 分类器配置：模型、温度、上下文截断与超时。
 *
 * <p>timeout-seconds 是分类 HTTP 单次尝试的读写超时；分类器最多重试 3 次，
 * 该值必须远小于 Run 总时限（60s），避免慢分类耗尽 Agent 执行预算。</p>
 */
@ConfigurationProperties(prefix = "taxiagent.agent.classifier")
public class ClassifierProperties {

    private String model = "qwen3-max-preview";
    private double temperature = 0.5;
    private int maxHistoryText = 500;
    private int timeoutSeconds = 5;

    /**
     * 分类模型名称。
     *
     * @return 模型名称
     */
    public String getModel() {
        return model;
    }

    /**
     * 设置分类模型名称。
     *
     * @param model 模型名称
     */
    public void setModel(String model) {
        this.model = model;
    }

    /**
     * 分类采样温度。
     *
     * @return 温度值
     */
    public double getTemperature() {
        return temperature;
    }

    /**
     * 设置分类采样温度。
     *
     * @param temperature 温度值
     */
    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    /**
     * 注入分类器上下文时最近一条助手回复的最大截断长度。
     *
     * @return 最大字符数
     */
    public int getMaxHistoryText() {
        return maxHistoryText;
    }

    /**
     * 设置助手回复截断长度。
     *
     * @param maxHistoryText 最大字符数
     */
    public void setMaxHistoryText(int maxHistoryText) {
        this.maxHistoryText = maxHistoryText;
    }

    /**
     * 分类 HTTP 单次尝试的读写超时（秒），重试上限 3 次。
     *
     * @return 超时秒数
     */
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * 设置分类 HTTP 单次尝试的读写超时（秒）。
     *
     * @param timeoutSeconds 超时秒数
     */
    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
