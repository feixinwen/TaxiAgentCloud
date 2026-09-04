package com.fancy.taxiagent.user.config;

import com.fancy.taxiagent.user.filter.RequestTraceFilter;
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
 * 记录 User Service 的认证与授权拒绝事件，并委托 Spring Security 返回标准 401/403 响应。
 *
 * <p>拒绝日志不包含 Authorization 请求头或 Token 内容，避免凭证进入日志系统。</p>
 */
@Component
public class UserSecurityFailureHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(UserSecurityFailureHandler.class);

    private final BearerTokenAuthenticationEntryPoint authenticationDelegate =
            new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler accessDeniedDelegate =
            new BearerTokenAccessDeniedHandler();

    /**
     * 记录未认证请求并返回标准 Bearer Token 401 响应。
     */
    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException, ServletException {
        log.warn(
                "event=user_authentication_rejected traceId={} method={} path={} reason={}",
                response.getHeader(RequestTraceFilter.TRACE_ID_HEADER),
                request.getMethod(),
                request.getRequestURI(),
                exception.getClass().getSimpleName()
        );
        authenticationDelegate.commence(request, response, exception);
    }

    /**
     * 记录身份合法但权限不足的请求并返回标准 Bearer Token 403 响应。
     */
    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception
    ) throws IOException, ServletException {
        log.warn(
                "event=user_access_denied traceId={} method={} path={} reason={}",
                response.getHeader(RequestTraceFilter.TRACE_ID_HEADER),
                request.getMethod(),
                request.getRequestURI(),
                exception.getClass().getSimpleName()
        );
        accessDeniedDelegate.handle(request, response, exception);
    }
}
