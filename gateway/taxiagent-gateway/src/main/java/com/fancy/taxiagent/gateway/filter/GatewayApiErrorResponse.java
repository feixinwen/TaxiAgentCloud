package com.fancy.taxiagent.gateway.filter;

import java.time.Instant;

/**
 * Gateway 层错误响应的统一结构，镜像 Auth 服务的 {@code ApiErrorResponse}。
 *
 * @param code     错误码，例如 {@code TOKEN_REVOKED}
 * @param message  面向用户的可读错误信息
 * @param traceId  追踪标识
 * @param timestamp 错误发生时间
 */
public record GatewayApiErrorResponse(String code, String message, String traceId, Instant timestamp) {
}
