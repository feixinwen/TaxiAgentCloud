package com.fancy.taxiagent.agent.controller;

import com.fancy.taxiagent.agent.exception.AgentApiException;
import com.fancy.taxiagent.agent.filter.RequestTraceFilter;
import com.fancy.taxiagent.agent.response.ApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * Agent HTTP 接口的统一异常映射。
 */
@RestControllerAdvice(assignableTypes = {AgentController.class, AgentHealthController.class})
public class AgentExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentExceptionHandler.class);

    /**
     * 映射已知且可安全公开的业务错误。
     */
    @ExceptionHandler(AgentApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(AgentApiException exception) {
        return ResponseEntity.status(exception.getStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(
                        exception.getCode(),
                        exception.getMessage(),
                        MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY),
                        Instant.now(),
                        exception.getRunId()
                ));
    }

    /**
     * 将消息 DTO 校验失败或 JSON/UUID 解析失败映射为稳定的安全错误。
     *
     * <p>此处不记录异常对象，避免校验异常携带的 rejected value 泄露消息正文。</p>
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiErrorResponse> handleInvalidMessage(Exception exception) {
        log.warn("event=agent_invalid_message traceId={}", MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY));
        return error(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE", "消息内容或标识不合法");
    }

    /**
     * 保留 Spring Security 的方法级授权处理链。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public void handleAccessDenied(AccessDeniedException exception) throws AccessDeniedException {
        throw exception;
    }

    /**
     * 隐藏未知内部异常，只记录安全元数据并对外返回稳定错误。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        log.error(
                "event=agent_api_unexpected_error traceId={} exceptionType={}",
                MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY),
                exception.getClass().getSimpleName()
        );
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误");
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(
                        code,
                        message,
                        MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY),
                        Instant.now()
                ));
    }
}
