package com.fancy.taxiagent.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 为所有 Gateway HTTP 请求传播或生成追踪标识，并记录请求边界日志。
 *
 * <p>使用 WebFilter 而不是仅作用于已匹配路由的 GlobalFilter，确保被 Spring Security
 * 提前拒绝的 401/403 请求也能够追踪。</p>
 */
@Component
public class RequestTraceFilter implements WebFilter, Ordered {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private static final int TRACE_ID_MAX_LENGTH = 128;
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Logger log = LoggerFactory.getLogger(RequestTraceFilter.class);

    /**
     * 在安全过滤链之前补充追踪标识，并将该标识继续传给下游路由。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = resolveTraceId(exchange.getRequest().getHeaders().getFirst(TRACE_ID_HEADER));
        long startNanos = System.nanoTime();
        ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .headers(headers -> headers.set(TRACE_ID_HEADER, traceId))
                .build();
        ServerWebExchange tracedExchange = exchange.mutate().request(request).build();
        tracedExchange.getResponse().getHeaders().set(TRACE_ID_HEADER, traceId);

        log.info(
                "event=gateway_request_started traceId={} method={} path={}",
                traceId,
                request.getMethod(),
                request.getURI().getPath()
        );

        return chain.filter(tracedExchange)
                .doFinally(signalType -> log.info(
                        "event=gateway_request_finished traceId={} method={} path={} status={} durationMs={} signal={}",
                        traceId,
                        request.getMethod(),
                        request.getURI().getPath(),
                        tracedExchange.getResponse().getStatusCode() == null
                                ? 200
                                : tracedExchange.getResponse().getStatusCode().value(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos),
                        signalType
                ));
    }

    private String resolveTraceId(String candidate) {
        if (StringUtils.hasText(candidate)
                && candidate.length() <= TRACE_ID_MAX_LENGTH
                && SAFE_TRACE_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
