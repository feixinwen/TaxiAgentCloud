package com.fancy.taxiagent.agent.config;

import com.fancy.taxiagent.agent.classify.ClassifierProperties;
import com.fancy.taxiagent.agent.qweather.QweatherProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Agent 外呼 HTTP 客户端装配：分类器专用与和风天气专用 RestClient（均带超时）。
 */
@Configuration(proxyBeanMethods = false)
public class AgentHttpConfiguration {

    /**
     * 分类器专用 RestClient（无 baseUrl，调用时传完整 URL）。
     *
     * @param builder RestClient 构造器
     * @param properties 分类器配置
     * @return 分类器 RestClient
     */
    @Primary
    @Bean("dashScopeChatRestClient")
    public RestClient dashScopeChatRestClient(RestClient.Builder builder, ClassifierProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getTimeoutSeconds() * 1000);
        requestFactory.setReadTimeout(properties.getTimeoutSeconds() * 1000);
        return builder.requestFactory(requestFactory).build();
    }

    /**
     * 和风天气专用 RestClient（带 baseUrl 与超时，天气 API 按路径调用）。
     *
     * @param builder RestClient 构造器
     * @param properties 和风配置
     * @return 和风 RestClient
     */
    @Bean("qweatherRestClient")
    public RestClient qweatherRestClient(RestClient.Builder builder, QweatherProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getTimeoutSeconds() * 1000);
        requestFactory.setReadTimeout(properties.getTimeoutSeconds() * 1000);
        return builder.baseUrl(properties.getUrl()).requestFactory(requestFactory).build();
    }
}
