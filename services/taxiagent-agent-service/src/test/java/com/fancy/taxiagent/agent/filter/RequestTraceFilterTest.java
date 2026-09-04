package com.fancy.taxiagent.agent.filter;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 Agent Service 的 TraceId 安全传播与线程清理。
 */
class RequestTraceFilterTest {

    private final RequestTraceFilter filter = new RequestTraceFilter();

    @Test
    void shouldPropagateSafeTraceIdAndClearMdcAfterRequest() throws Exception {
        MockHttpServletRequest request = requestWithTraceId("trace-20260820_001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestTraceFilter.TRACE_ID_HEADER))
                .isEqualTo("trace-20260820_001");
        assertThat(MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY)).isNull();
    }

    @Test
    void shouldReplaceTraceIdContainingLogInjectionCharacters() throws Exception {
        MockHttpServletRequest request = requestWithTraceId("safe\r\nforged=true");
        HttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestTraceFilter.TRACE_ID_HEADER))
                .matches("[a-f0-9]{32}")
                .doesNotContain("forged");
        assertThat(MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY)).isNull();
    }

    @Test
    void shouldClearMdcWhenDownstreamFilterThrows() {
        MockHttpServletRequest request = requestWithTraceId("trace-on-error");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilterInternal(
                request,
                response,
                (servletRequest, servletResponse) -> {
                    throw new jakarta.servlet.ServletException("test failure");
                }
        )).isInstanceOf(jakarta.servlet.ServletException.class);

        assertThat(MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY)).isNull();
    }

    private MockHttpServletRequest requestWithTraceId(String traceId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/conversations");
        request.addHeader(RequestTraceFilter.TRACE_ID_HEADER, traceId);
        return request;
    }
}
