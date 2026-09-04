package com.fancy.taxiagent.rag.response;

import java.time.Instant;

/**
 * Rag Service HTTP 接口的稳定错误响应。
 *
 * @param code      可供调用方判断错误类型的稳定错误码
 * @param message   面向调用方的错误说明，不包含内部堆栈或敏感数据
 * @param traceId   用于关联 Gateway、服务日志和远程调用的追踪标识
 * @param timestamp 错误响应生成时间
 */
public record ApiErrorResponse(
        String code,
        String message,
        String traceId,
        Instant timestamp
) {
}
