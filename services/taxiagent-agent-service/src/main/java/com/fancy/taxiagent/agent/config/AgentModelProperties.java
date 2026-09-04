package com.fancy.taxiagent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SupportAgent 的模型名称与提示词版本配置。
 */
@ConfigurationProperties(prefix = "taxiagent.agent.model")
public class AgentModelProperties {

    private String apiKey;
    private String baseUrl = "https://api.deepseek.com";
    private String name = "deepseek-chat";
    private String promptVersion = "support-v1";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }
}
