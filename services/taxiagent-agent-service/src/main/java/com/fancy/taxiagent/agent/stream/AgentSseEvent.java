package com.fancy.taxiagent.agent.stream;

import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Instant;

/**
 * 面向单个 Agent SSE 连接的安全公开事件。
 *
 * @param id 单连接内严格递增的事件编号
 * @param name SSE 事件名称
 * @param data 可公开的事件数据
 */
public record AgentSseEvent(Long id, String name, Map<String, Object> data) {

    /**
     * 冻结事件数据，防止发布后被调用方修改。
     */
    public AgentSseEvent {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    /**
     * 创建 Run 已开始事件。
     *
     * @param runId 对外 Run 标识
     * @param conversationId 对外对话标识
     * @return 未分配连接事件编号的公开事件
     */
    public static AgentSseEvent runStarted(String runId, String conversationId) {
        return event("run.started", Map.of("runId", runId, "conversationId", conversationId));
    }

    /**
     * 创建工具开始事件。
     *
     * @param runId 对外 Run 标识
     * @param toolCallId 工具调用标识
     * @param toolName 工具名称
     * @return 未分配连接事件编号的公开事件
     */
    public static AgentSseEvent toolStarted(String runId, String toolCallId, String toolName) {
        return event("tool.started", Map.of("runId", runId, "toolCallId", toolCallId, "toolName", toolName));
    }

    /**
     * 创建工具完成事件。
     *
     * @param runId 对外 Run 标识
     * @param toolCallId 工具调用标识
     * @param toolName 工具名称
     * @param success 工具是否成功
     * @param durationMs 工具耗时毫秒数
     * @return 未分配连接事件编号的公开事件
     */
    public static AgentSseEvent toolCompleted(String runId, String toolCallId, String toolName, boolean success, long durationMs) {
        return event("tool.completed", Map.of(
                "runId", runId,
                "toolCallId", toolCallId,
                "toolName", toolName,
                "success", success,
                "durationMs", durationMs
        ));
    }

    /**
     * 创建助手正文增量事件。
     *
     * @param runId 对外 Run 标识
     * @param content 可展示的助手正文增量
     * @return 未分配连接事件编号的公开事件
     */
    public static AgentSseEvent messageDelta(String runId, String content) {
        return event("message.delta", Map.of("runId", runId, "content", content));
    }

    /**
     * 创建提示通知事件（如 ORDER 粘滞提示）。
     *
     * @param runId 对外 Run 标识
     * @param content 可展示的提示文本
     * @return 未分配连接事件编号的公开事件
     */
    public static AgentSseEvent notify(String runId, String content) {
        return event("notify", Map.of("runId", runId, "content", content));
    }

    /**
     * 创建订单确认事件（HITL 暂停，含订单摘要）。
     *
     * <p>route 为可空的路线数据（traceId/estDistance/startLat/startLng/endLat/endLng），
     * 条目平铺进事件 data 供前端直接取用画图；null 或空则事件与旧契约完全一致。</p>
     *
     * @param runId 对外 Run 标识
     * @param content 订单摘要 JSON
     * @param route 路线数据（可空）
     * @return 未分配连接事件编号的公开事件
     */
    public static AgentSseEvent confirm(String runId, String content, Map<String, Object> route) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", runId);
        data.put("content", content);
        if (route != null) {
            data.putAll(route);
        }
        return event("confirm", data);
    }

    /**
     * 创建消息持久化完成事件（messageId 以字符串承载 Snowflake 值，
     * 避免 19 位数字超出 JS Number.MAX_SAFE_INTEGER 造成精度丢失）。
     *
     * @param runId 对外 Run 标识
     * @param messageId 助手消息内部标识
     * @return 未分配连接事件编号的公开事件
     */
    public static AgentSseEvent messageCompleted(String runId, long messageId) {
        return event("message.completed", Map.of("runId", runId, "messageId", String.valueOf(messageId)));
    }

    /**
     * 创建 Run 成功完成事件。
     *
     * @param runId 对外 Run 标识
     * @return 未分配连接事件编号的公开事件
     */
    public static AgentSseEvent runCompleted(String runId) {
        return event("run.completed", Map.of("runId", runId));
    }

    /**
     * 创建 Run 安全失败事件。
     *
     * @param runId 对外 Run 标识
     * @param errorCode 稳定错误码
     * @param message 可安全公开的失败提示
     * @return 未分配连接事件编号的公开事件
     */
    public static AgentSseEvent runFailed(String runId, String errorCode, String message) {
        return event("run.failed", Map.of("runId", runId, "errorCode", errorCode, "message", message));
    }

    /**
     * 创建空闲连接心跳事件。
     *
     * @param runId 对外 Run 标识
     * @return 未分配连接事件编号的公开事件
     */
    public static AgentSseEvent heartbeat(String runId) {
        return event("heartbeat", Map.of("runId", runId, "timestamp", Instant.now().toString()));
    }

    /**
     * 为事件绑定单连接序号。
     *
     * @param assignedId 已分配的事件序号
     * @return 已分配序号的新事件
     */
    public AgentSseEvent withId(long assignedId) {
        return new AgentSseEvent(assignedId, name, data);
    }

    private static AgentSseEvent event(String name, Map<String, Object> data) {
        return new AgentSseEvent(null, name, new LinkedHashMap<>(data));
    }
}
