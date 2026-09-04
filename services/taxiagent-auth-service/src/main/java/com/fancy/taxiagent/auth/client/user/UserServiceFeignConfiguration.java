package com.fancy.taxiagent.auth.client.user;

import com.fancy.taxiagent.auth.config.ServiceIdentityProperties;
import com.fancy.taxiagent.auth.filter.RequestTraceFilter;
import com.fancy.taxiagent.auth.service.ServiceTokenService;
import com.fancy.taxiagent.auth.service.dto.IssuedServiceToken;
import com.fancy.taxiagent.auth.service.dto.ServiceTokenRequest;
import feign.RequestInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;

import java.util.Set;
import java.util.UUID;

/**
 * 仅作用于 User Service Feign 客户端的服务身份与追踪配置。
 *
 * <p>此类不声明为全局 Configuration，避免把 User Service 凭证错误附加到其他下游请求。</p>
 */
public class UserServiceFeignConfiguration {

    private static final Logger log = LoggerFactory.getLogger(UserServiceFeignConfiguration.class);

    @Bean
    RequestInterceptor userServiceIdentityInterceptor(
            ObjectProvider<ServiceTokenService> serviceTokenServiceProvider,
            ServiceIdentityProperties properties
    ) {
        return requestTemplate -> {
            String audience = requireText(properties.getUserServiceAudience(), "User Service audience");
            Set<String> scopes = resolveScopes(requestTemplate.method(), properties.getUserServiceScopes());
            IssuedServiceToken token = serviceTokenServiceProvider.getObject()
                    .issue(new ServiceTokenRequest(audience, scopes));
            String traceId = currentTraceId();

            requestTemplate.removeHeader(HttpHeaders.AUTHORIZATION);
            requestTemplate.header(HttpHeaders.AUTHORIZATION, token.tokenType() + " " + token.tokenValue());
            requestTemplate.removeHeader(RequestTraceFilter.TRACE_ID_HEADER);
            requestTemplate.header(RequestTraceFilter.TRACE_ID_HEADER, traceId);
            log.debug(
                    "event=auth_user_feign_request_prepared traceId={} method={} path={} audience={} scopes={}",
                    traceId,
                    requestTemplate.method(),
                    requestTemplate.path(),
                    audience,
                    scopes
            );
        };
    }

    private Set<String> resolveScopes(String method, Set<String> allowedScopes) {
        String requiredScope = switch (method) {
            case "GET", "PATCH" -> "user:read";
            case "PUT", "POST" -> "user:write";
            default -> throw new IllegalStateException("User Service Feign 方法未配置服务权限: " + method);
        };
        if (allowedScopes == null || !allowedScopes.contains(requiredScope)) {
            throw new IllegalStateException("Auth Service 未获准使用 User Service scope: " + requiredScope);
        }
        return Set.of(requiredScope);
    }

    private String currentTraceId() {
        String traceId = MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY);
        return traceId == null || traceId.isBlank()
                ? UUID.randomUUID().toString().replace("-", "")
                : traceId;
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(fieldName + " 不能为空");
        }
        return value.trim();
    }
}
