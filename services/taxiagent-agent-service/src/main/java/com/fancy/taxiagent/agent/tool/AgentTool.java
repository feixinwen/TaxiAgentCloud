package com.fancy.taxiagent.agent.tool;

import com.fancy.taxiagent.agent.domain.model.AgentExecutionContext;
import com.fancy.taxiagent.agent.stream.AgentStreamSink;

/**
 * Agent 可执行工具的稳定契约：模型按描述生成调用，Agent 循环显式执行。
 */
public interface AgentTool {

    /**
     * 工具名（模型可见，必须与注册名一致）。
     *
     * @return 工具名
     */
    String name();

    /**
     * 工具描述（含参数说明，模型据此生成 JSON 参数）。
     *
     * @return 模型可见描述
     */
    String description();

    /**
     * 工具参数类型（模型据此生成 JSON 参数 schema）。
     *
     * @return 参数 record 类型
     */
    Class<?> inputType();

    /**
     * 执行工具调用。
     *
     * @param jsonArgs 模型生成的 JSON 参数
     * @param context 当前 Run 上下文（含透传授权头）
     * @param sink 流事件出口（可选使用）
     * @return 返回给模型的文本结果；失败时返回以 "Tool execution failed: " 开头的说明
     */
    String execute(String jsonArgs, AgentExecutionContext context, AgentStreamSink sink);
}
