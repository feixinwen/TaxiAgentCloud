package com.fancy.taxiagent.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestTraceFilterTest {

    private final RequestTraceFilter filter = new RequestTraceFilter();

    @Test
    void shouldPreserveAndForwardExistingTraceId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/users/ping")
                        .header(RequestTraceFilter.TRACE_ID_HEADER, "trace-123")
                        .build()
        );
        AtomicReference<ServerWebExchange> forwardedExchange = new AtomicReference<>();
        WebFilterChain chain = currentExchange -> {
            forwardedExchange.set(currentExchange);
            currentExchange.getResponse().setStatusCode(HttpStatus.OK);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(forwardedExchange.get()).isNotNull();
        assertThat(forwardedExchange.get().getRequest().getHeaders()
                .getFirst(RequestTraceFilter.TRACE_ID_HEADER)).isEqualTo("trace-123");
        assertThat(exchange.getResponse().getHeaders()
                .getFirst(RequestTraceFilter.TRACE_ID_HEADER)).isEqualTo("trace-123");
    }
}
