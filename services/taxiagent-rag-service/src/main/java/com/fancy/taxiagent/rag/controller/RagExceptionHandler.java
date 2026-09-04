package com.fancy.taxiagent.rag.controller;

import com.fancy.taxiagent.rag.exception.EmbeddingErrorException;
import com.fancy.taxiagent.rag.exception.InvalidRagRequestException;
import com.fancy.taxiagent.rag.exception.QaNotFoundException;
import com.fancy.taxiagent.rag.filter.RequestTraceFilter;
import com.fancy.taxiagent.rag.response.ApiErrorResponse;
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
 * RAG 接口的统一异常映射。
 */
@RestControllerAdvice(assignableTypes = {RagAdminController.class, RagSearchController.class, RagHealthController.class})
public class RagExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RagExceptionHandler.class);

    @ExceptionHandler(InvalidRagRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalid(InvalidRagRequestException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(QaNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(QaNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "QA_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(EmbeddingErrorException.class)
    public ResponseEntity<ApiErrorResponse> handleEmbedding(EmbeddingErrorException exception) {
        log.warn("event=rag_embedding_error message={}", exception.getMessage());
        return error(HttpStatus.BAD_GATEWAY, "EMBEDDING_ERROR", exception.getMessage());
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
        throw exception;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        log.error("event=rag_api_unexpected_error", exception);
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
