package com.fancy.taxiagent.agent.controller;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fancy.taxiagent.agent.filter.RequestTraceFilter;
import com.fancy.taxiagent.agent.response.ApiErrorResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 验证消息请求格式错误的稳定 HTTP 映射。
 */
class AgentExceptionHandlerTest {

    @Test
    void shouldMapValidationAndMalformedJsonToInvalidMessage() throws Exception {
        AgentExceptionHandler handler = new AgentExceptionHandler();
        Method method = AgentExceptionHandler.class.getMethod("handleInvalidMessage", Exception.class);
        ExceptionHandler annotation = method.getAnnotation(ExceptionHandler.class);

        assertThat(Arrays.asList(annotation.value()))
                .contains(MethodArgumentNotValidException.class, HttpMessageNotReadableException.class);

        ResponseEntity<ApiErrorResponse> response = handler.handleInvalidMessage(
                mock(MethodArgumentNotValidException.class)
        );
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INVALID_MESSAGE");
    }

    @Test
    void shouldLogUnexpectedExceptionMetadataWithoutSensitiveDetails() {
        AgentExceptionHandler handler = new AgentExceptionHandler();
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AgentExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.put(RequestTraceFilter.TRACE_ID_MDC_KEY, "trace-error-801");

        try {
            handler.handleUnexpected(new IllegalStateException("sentinel-sensitive-exception-message"));

            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.getFirst();
            assertThat(event.getFormattedMessage())
                    .contains(
                            "event=agent_api_unexpected_error",
                            "traceId=trace-error-801",
                            "exceptionType=IllegalStateException")
                    .doesNotContain("sentinel-sensitive-exception-message");
            assertThat(event.getThrowableProxy()).isNull();
        } finally {
            MDC.remove(RequestTraceFilter.TRACE_ID_MDC_KEY);
            logger.detachAppender(appender);
        }
    }
}
