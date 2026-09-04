package com.fancy.taxiagent.user.controller;

import com.fancy.taxiagent.user.location.InvalidLocationException;
import com.fancy.taxiagent.user.location.LocationNotFoundException;
import com.fancy.taxiagent.user.poi.InvalidPoiException;
import com.fancy.taxiagent.user.poi.PoiNotFoundException;
import com.fancy.taxiagent.user.response.ApiErrorResponse;
import com.fancy.taxiagent.user.filter.RequestTraceFilter;
import org.springframework.security.access.AccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * 用户公开接口（位置、POI 等）的统一异常映射。
 */
@RestControllerAdvice(assignableTypes = {UserLocationController.class, UserPoiController.class})
public class UserPublicApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(UserPublicApiExceptionHandler.class);

    @ExceptionHandler(LocationNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleLocationNotFound(LocationNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "LOCATION_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(InvalidLocationException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidLocation(InvalidLocationException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_LOCATION", exception.getMessage());
    }

    @ExceptionHandler(PoiNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handlePoiNotFound(PoiNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "POI_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(InvalidPoiException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidPoi(InvalidPoiException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_POI", exception.getMessage());
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
        log.error("event=user_public_api_unexpected_error", exception);
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
