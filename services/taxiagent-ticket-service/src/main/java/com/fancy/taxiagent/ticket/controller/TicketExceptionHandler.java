package com.fancy.taxiagent.ticket.controller;

import com.fancy.taxiagent.ticket.exception.InvalidTicketRequestException;
import com.fancy.taxiagent.ticket.exception.TicketNotFoundException;
import com.fancy.taxiagent.ticket.exception.TicketStateConflictException;
import com.fancy.taxiagent.ticket.filter.RequestTraceFilter;
import com.fancy.taxiagent.ticket.response.ApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * 工单接口的统一异常映射。
 */
@RestControllerAdvice(assignableTypes = {TicketController.class, TicketHealthController.class})
public class TicketExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(TicketExceptionHandler.class);

    @ExceptionHandler(InvalidTicketRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalid(InvalidTicketRequestException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(TicketNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(TicketNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(TicketStateConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(TicketStateConflictException exception) {
        return error(HttpStatus.CONFLICT, "TICKET_STATE_CONFLICT", exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("请求参数不合法");
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求体格式不合法");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public void handleAccessDenied(AccessDeniedException exception) throws AccessDeniedException {
        // 重新抛出由 Spring Security 的 AccessDeniedHandler 统一返回 403
        throw exception;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        log.error("event=ticket_api_unexpected_error", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误");
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                code,
                message,
                MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY),
                Instant.now()));
    }
}
