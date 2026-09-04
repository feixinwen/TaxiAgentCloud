package com.fancy.taxiagent.user.controller;

import com.fancy.taxiagent.user.exception.InvalidUserProfileException;
import com.fancy.taxiagent.user.exception.UserProfileConflictException;
import com.fancy.taxiagent.user.exception.UserProfileNotFoundException;
import com.fancy.taxiagent.user.filter.RequestTraceFilter;
import com.fancy.taxiagent.user.response.ApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;

/**
 * User Service 内部用户资料 API 的统一异常映射。
 */
@RestControllerAdvice(assignableTypes = InternalUserProfileController.class)
public class UserApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(UserApiExceptionHandler.class);

    /**
     * 将资料字段或业务约束错误映射为 HTTP 400。
     */
    @ExceptionHandler(InvalidUserProfileException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidProfile(InvalidUserProfileException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_USER_PROFILE", exception.getMessage());
    }

    /**
     * 将 HTTP 请求体校验错误映射为稳定的 HTTP 400 响应。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("请求参数不合法");
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    /**
     * 将无法解析的 JSON 请求映射为 HTTP 400，避免向调用方暴露框架内部异常。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableRequest(HttpMessageNotReadableException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求体格式不合法");
    }

    /**
     * 将缺少查询参数或路径参数类型错误映射为 HTTP 400。
     */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiErrorResponse> handleRequestBinding(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求参数不合法");
    }

    /**
     * 将用户资料唯一性冲突映射为 HTTP 409。
     */
    @ExceptionHandler(UserProfileConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(UserProfileConflictException exception) {
        return error(HttpStatus.CONFLICT, "USER_PROFILE_CONFLICT", exception.getMessage());
    }

    /**
     * 将用户资料不存在映射为 HTTP 404。
     */
    @ExceptionHandler(UserProfileNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(UserProfileNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "USER_PROFILE_NOT_FOUND", exception.getMessage());
    }

    /**
     * 记录未预期异常的完整堆栈，并向调用方隐藏内部实现细节。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
        log.error("event=user_api_unexpected_error", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误");
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
