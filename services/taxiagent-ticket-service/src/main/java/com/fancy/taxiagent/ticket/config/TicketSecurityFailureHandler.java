package com.fancy.taxiagent.ticket.config;

import com.fancy.taxiagent.ticket.filter.RequestTraceFilter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 记录 Ticket Service 的认证与授权拒绝事件，并委托 Spring Security 返回标准 401/403 响应。
 *
 * <p>拒绝日志不包含 Authorization 请求头或 Token 内容，避免凭证进入日志系统。</p>
 */
@Component
public class TicketSecurityFailureHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(TicketSecurityFailureHandler.class);

    private final BearerTokenAuthenticationEntryPoint authenticationDelegate =
            new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler accessDeniedDelegate =
            new BearerTokenAccessDeniedHandler();

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException, ServletException {
        log.warn(
                "event=order_authentication_rejected traceId={} method={} path={} reason={}",
                response.getHeader(RequestTraceFilter.TRACE_ID_HEADER),
                request.getMethod(),
                request.getRequestURI(),
                exception.getClass().getSimpleName()
        );
        authenticationDelegate.commence(request, response, exception);
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception
    ) throws IOException, ServletException {
        log.warn(
                "event=order_access_denied traceId={} method={} path={} reason={}",
                response.getHeader(RequestTraceFilter.TRACE_ID_HEADER),
                request.getMethod(),
                request.getRequestURI(),
                exception.getClass().getSimpleName()
        );
        accessDeniedDelegate.handle(request, response, exception);
    }
}
