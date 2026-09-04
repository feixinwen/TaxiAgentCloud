package com.fancy.taxiagent.agent.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent 工具注册表：维护名称到执行器的映射，并为模型生成工具定义。
 */
@Service
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();
    private final List<ToolCallback> toolCallbacks = new CopyOnWriteArrayList<>();

    /**
     * 自动注册全部 AgentTool Bean。
     *
     * @param toolBeans 容器中的工具执行器列表
     */
    public ToolRegistry(List<AgentTool> toolBeans) {
        toolBeans.forEach(this::register);
    }

    /**
     * 注册工具（幂等：同名覆盖映射与工具定义，避免旧定义残留导致模型工具列表重复）。
     *
     * @param tool 工具执行器
     */
    public void register(AgentTool tool) {
        tools.put(tool.name(), tool);
        toolCallbacks.removeIf(callback -> callback.getToolDefinition().name().equals(tool.name()));
        toolCallbacks.add(toCallback(tool));
    }

    /**
     * 按名称查找工具。
     *
     * @param name 工具名
     * @return 工具执行器；未注册返回 null
     */
    public AgentTool find(String name) {
        return tools.get(name);
    }

    /**
     * 当前已注册工具名集合。
     *
     * @return 工具名集合
     */
    public Set<String> names() {
        return tools.keySet();
    }

    /**
     * 模型工具定义（供 Spring AI 注入，工具由 Agent 显式执行）。
     *
     * @return 工具回调列表
     */
    public List<ToolCallback> toolCallbacks() {
        return List.copyOf(toolCallbacks);
    }

    private ToolCallback toCallback(AgentTool tool) {
        return FunctionToolCallback
                .<Object, String>builder(tool.name(), arguments -> {
                    throw new IllegalStateException("模型工具必须由 Agent 显式执行");
                })
                .description(tool.description())
                .inputType(tool.inputType())
                .build();
    }
}
