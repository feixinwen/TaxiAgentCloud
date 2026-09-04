package com.fancy.taxiagent.agent.config;

import com.fancy.taxiagent.agent.model.AgentModelGateway;
import com.fancy.taxiagent.agent.model.SpringAiAgentModelGateway;
import com.fancy.taxiagent.agent.model.UnavailableAgentModelGateway;
import com.fancy.taxiagent.agent.tool.ToolRegistry;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 根据模型密钥是否存在选择 Agent 模型网关。
 */
@Configuration(proxyBeanMethods = false)
public class AgentModelConfiguration {

    /**
     * 创建 Agent 模型网关；密钥为空时使用稳定的不可用实现。
     *
     * @param properties Agent 模型配置
     * @return 可供 SupportAgent 使用的模型网关
     */
    @Bean
    AgentModelGateway agentModelGateway(AgentModelProperties properties, ToolRegistry toolRegistry) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            return new UnavailableAgentModelGateway();
        }
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(properties.getName())
                        .build())
                .build();
        return new SpringAiAgentModelGateway(chatModel, properties, toolRegistry.toolCallbacks());
    }
}
