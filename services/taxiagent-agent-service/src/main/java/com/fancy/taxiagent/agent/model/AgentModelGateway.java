package com.fancy.taxiagent.agent.model;

import java.time.Duration;
import java.util.Set;
import java.util.function.Consumer;

/**
 * SupportAgent 访问流式大模型的内部防腐接口。
 */
public interface AgentModelGateway {

    /**
     * 流式执行一轮模型调用，并返回聚合后的助手消息。
     *
     * @param request 模型请求
     * @param allowedToolNames 本 Agent 允许模型调用的工具名集合；空集合表示不向模型暴露任何工具
     * @param deltaConsumer 公开正文增量消费者
     * @param timeout 本轮模型调用可使用的剩余 Run 时间
     * @return 聚合后的助手轮次
     */
    ModelTurn stream(ModelRequest request, Set<String> allowedToolNames,
                     Consumer<String> deltaConsumer, Duration timeout);
}
