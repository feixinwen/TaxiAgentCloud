package com.fancy.taxiagent.gateway.config;

import com.fancy.taxiagent.gateway.filter.RequestTraceFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.server.BearerTokenServerAccessDeniedHandler;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 记录 Gateway 的认证与授权拒绝事件，并委托 Spring Security 生成标准 401/403 响应。
 *
 * <p>日志只记录异常类型和请求定位信息，不记录 Authorization 头或 Token 内容。</p>
 */
@Component
public class GatewaySecurityFailureHandler implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewaySecurityFailureHandler.class);

    private final BearerTokenServerAuthenticationEntryPoint authenticationDelegate =
            new BearerTokenServerAuthenticationEntryPoint();
    private final BearerTokenServerAccessDeniedHandler accessDeniedDelegate =
            new BearerTokenServerAccessDeniedHandler();

    /**
     * 记录未认证请求并返回标准 Bearer Token 401 响应。
     */
    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException exception) {
        log.warn(
                "event=gateway_authentication_rejected traceId={} method={} path={} reason={}",
                traceId(exchange),
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value(),
                exception.getClass().getSimpleName()
        );
        return authenticationDelegate.commence(exchange, exception);
    }

    /**
     * 记录身份合法但权限不足的请求并返回标准 403 响应。
     */
    @Override
    public Mono<Void> handle(ServerWebExchange exchange, org.springframework.security.access.AccessDeniedException exception) {
        log.warn(
                "event=gateway_access_denied traceId={} method={} path={} reason={}",
                traceId(exchange),
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value(),
                exception.getClass().getSimpleName()
        );
        return accessDeniedDelegate.handle(exchange, exception);
    }

    private String traceId(ServerWebExchange exchange) {
        return exchange.getRequest().getHeaders().getFirst(RequestTraceFilter.TRACE_ID_HEADER);
    }
}
