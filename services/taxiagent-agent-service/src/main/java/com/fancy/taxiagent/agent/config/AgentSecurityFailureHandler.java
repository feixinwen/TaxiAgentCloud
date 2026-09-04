package com.fancy.taxiagent.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.taxiagent.agent.filter.RequestTraceFilter;
import com.fancy.taxiagent.agent.response.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * 将 Agent Service 的认证与授权拒绝映射为稳定 JSON，并记录安全审计日志。
 */
@Component
public class AgentSecurityFailureHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentSecurityFailureHandler.class);

    private final ObjectMapper objectMapper;

    public AgentSecurityFailureHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 返回不包含 Token 或异常细节的 401 响应。
     */
    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {
        log.warn(
                "event=agent_authentication_rejected traceId={} method={} path={} reason={}",
                traceId(response),
                request.getMethod(),
                request.getRequestURI(),
                exception.getClass().getSimpleName()
        );
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "请求未通过身份认证");
    }

    /**
     * 返回不包含 Token 或异常细节的 403 响应。
     */
    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception
    ) throws IOException {
        log.warn(
                "event=agent_access_denied traceId={} method={} path={} reason={}",
                traceId(response),
                request.getMethod(),
                request.getRequestURI(),
                exception.getClass().getSimpleName()
        );
        writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "没有访问该资源的权限");
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
                code,
                message,
                traceId(response),
                Instant.now()
        ));
    }

    private String traceId(HttpServletResponse response) {
        return response.getHeader(RequestTraceFilter.TRACE_ID_HEADER);
    }
}
