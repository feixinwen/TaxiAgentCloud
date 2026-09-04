package com.fancy.taxiagent.agent.controller;

import com.fancy.taxiagent.agent.filter.RequestTraceFilter;
import com.fancy.taxiagent.agent.response.ApiErrorResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;

/**
 * 仅负责没有业务控制器归属的 HTTP 路由错误，避免全局吞掉框架或 Actuator 异常。
 */
@RestControllerAdvice
public class AgentRoutingExceptionHandler {

    /**
     * 将未注册的请求路径映射为稳定 404，避免返回框架内部资源信息。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(NoResourceFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "请求的资源不存在");
    }

    /**
     * 将不支持的 HTTP 方法映射为稳定 405。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception
    ) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "请求方法不受支持");
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                code,
                message,
                MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY),
                Instant.now()
        ));
    }
}
