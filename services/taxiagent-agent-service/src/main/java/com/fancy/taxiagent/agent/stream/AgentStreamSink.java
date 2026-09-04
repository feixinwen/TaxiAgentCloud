package com.fancy.taxiagent.agent.stream;

import java.util.Map;

/**
 * SupportAgent 可发布的公开流事件边界。
 */
public interface AgentStreamSink {

    /**
     * 发布工具开始事件，不包含参数和鉴权信息。
     *
     * @param toolCallId 工具调用标识
     * @param toolName 工具名称
     */
    void toolStarted(String toolCallId, String toolName);

    /**
     * 发布工具完成事件，不包含工具响应正文。
     *
     * @param toolCallId 工具调用标识
     * @param toolName 工具名称
     * @param success 是否成功
     * @param durationMs 耗时毫秒数
     */
    void toolCompleted(String toolCallId, String toolName, boolean success, long durationMs);

    /**
     * 发布可向用户展示的助手正文增量。
     *
     * @param content 正文增量
     */
    void messageDelta(String content);

    /**
     * 发布订单确认事件（HITL 暂停，内容为订单摘要 JSON）。
     *
     * @param content 订单摘要 JSON
     * @param route 路线数据（traceId/estDistance/起终点经纬度），条目平铺进事件 data；
     *              null 或空则不携带路线字段
     */
    void confirm(String content, Map<String, Object> route);
}
