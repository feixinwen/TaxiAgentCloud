package com.fancy.taxiagent.agent.model;

import com.fancy.taxiagent.agent.exception.AgentExecutionException;

import java.time.Duration;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 未配置模型密钥时使用的模型网关，避免阻断 Agent Service 的非模型能力。
 */
public final class UnavailableAgentModelGateway implements AgentModelGateway {

    /**
     * 以稳定错误码拒绝模型调用。
     *
     * @param request 模型请求
     * @param allowedToolNames 本 Agent 允许模型调用的工具名集合
     * @param deltaConsumer 正文增量消费者
     * @param timeout 本轮模型调用的剩余时间
     * @return 不会正常返回
     */
    @Override
    public ModelTurn stream(ModelRequest request, Set<String> allowedToolNames,
                            Consumer<String> deltaConsumer, Duration timeout) {
        throw new AgentExecutionException("MODEL_UNAVAILABLE", "模型服务暂不可用", null);
    }
}
