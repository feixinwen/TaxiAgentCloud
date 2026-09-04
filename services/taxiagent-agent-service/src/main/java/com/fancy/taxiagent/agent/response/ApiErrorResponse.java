package com.fancy.taxiagent.agent.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Agent Service 对外稳定错误响应。
 *
 * @param code 稳定错误码
 * @param message 安全错误说明
 * @param traceId 请求追踪标识
 * @param timestamp 错误发生时间
 * @param runId 重复消息已关联的 Run ID，其他错误为空
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        String code,
        String message,
        String traceId,
        Instant timestamp,
        String runId
) {

    public ApiErrorResponse(String code, String message, String traceId, Instant timestamp) {
        this(code, message, traceId, timestamp, null);
    }
}
