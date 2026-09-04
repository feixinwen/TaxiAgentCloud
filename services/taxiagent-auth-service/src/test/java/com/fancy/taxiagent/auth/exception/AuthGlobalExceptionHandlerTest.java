package com.fancy.taxiagent.auth.exception;

import com.fancy.taxiagent.auth.filter.RequestTraceFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class AuthGlobalExceptionHandlerTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldMapAuthApiExceptionToUnifiedResponse() {
        MDC.put(RequestTraceFilter.TRACE_ID_MDC_KEY, "test-trace-id");
        AuthGlobalExceptionHandler handler = new AuthGlobalExceptionHandler();

        ResponseEntity<ApiErrorResponse> response = handler.handleAuthApi(
                new AuthApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "该邮箱已注册"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("EMAIL_ALREADY_REGISTERED");
        assertThat(response.getBody().message()).isEqualTo("该邮箱已注册");
        assertThat(response.getBody().traceId()).isEqualTo("test-trace-id");
        assertThat(response.getBody().timestamp()).isNotNull();
    }
}
