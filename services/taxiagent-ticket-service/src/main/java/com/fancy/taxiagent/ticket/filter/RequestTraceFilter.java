package com.fancy.taxiagent.ticket.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 为 Ticket Service 的 HTTP 请求传播或生成追踪标识，并记录请求边界日志。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_MDC_KEY = "traceId";

    private static final int TRACE_ID_MAX_LENGTH = 128;
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Logger log = LoggerFactory.getLogger(RequestTraceFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(TRACE_ID_HEADER));
        long startNanos = System.nanoTime();
        MDC.put(TRACE_ID_MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        log.info(
                "event=ticket_http_request_started traceId={} method={} path={}",
                traceId,
                request.getMethod(),
                request.getRequestURI()
        );
        try {
            filterChain.doFilter(request, response);
        } finally {
            log.info(
                    "event=ticket_http_request_finished traceId={} method={} path={} status={} durationMs={}",
                    traceId,
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
            );
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }

    private String resolveTraceId(String candidate) {
        if (StringUtils.hasText(candidate)
                && candidate.length() <= TRACE_ID_MAX_LENGTH
                && SAFE_TRACE_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
