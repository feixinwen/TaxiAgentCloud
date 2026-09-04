package com.fancy.taxiagent.auth.client.user;

import com.fancy.taxiagent.auth.config.ServiceIdentityProperties;
import com.fancy.taxiagent.auth.filter.RequestTraceFilter;
import com.fancy.taxiagent.auth.service.ServiceTokenService;
import com.fancy.taxiagent.auth.service.dto.IssuedServiceToken;
import com.fancy.taxiagent.auth.service.dto.ServiceTokenRequest;
import feign.RequestInterceptor;
import feign.Request;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 User Service Feign 客户端只附加服务 Token，并正确传播 traceId。
 */
class UserServiceFeignConfigurationTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldAttachAudienceBoundServiceTokenAndTraceId() {
        ServiceTokenService tokenService = mock(ServiceTokenService.class);
        ServiceIdentityProperties properties = new ServiceIdentityProperties();
        properties.setUserServiceAudience("taxiagent-user-service");
        properties.setUserServiceScopes(Set.of("user:read", "user:write"));
        Instant now = Instant.now();
        when(tokenService.issue(org.mockito.ArgumentMatchers.any())).thenReturn(
                new IssuedServiceToken("signed-service-token", "Bearer", now, now.plusSeconds(60))
        );
        ObjectProvider<ServiceTokenService> tokenServiceProvider = tokenServiceProvider(tokenService);
        RequestInterceptor interceptor = new UserServiceFeignConfiguration()
                .userServiceIdentityInterceptor(tokenServiceProvider, properties);
        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.GET);
        template.append("/internal/users/10001/profile");
        MDC.put(RequestTraceFilter.TRACE_ID_MDC_KEY, "trace-feign-10001");

        interceptor.apply(template);

        assertThat(template.headers().get(HttpHeaders.AUTHORIZATION))
                .containsExactly("Bearer signed-service-token");
        assertThat(template.headers().get(RequestTraceFilter.TRACE_ID_HEADER))
                .containsExactly("trace-feign-10001");
        ArgumentCaptor<ServiceTokenRequest> requestCaptor = ArgumentCaptor.forClass(ServiceTokenRequest.class);
        verify(tokenService).issue(requestCaptor.capture());
        assertThat(requestCaptor.getValue().audience()).isEqualTo("taxiagent-user-service");
        assertThat(requestCaptor.getValue().scopes()).containsExactly("user:read");
    }

    @Test
    void shouldRequestOnlyWriteScopeForPutOperation() {
        ServiceTokenService tokenService = mock(ServiceTokenService.class);
        ServiceIdentityProperties properties = new ServiceIdentityProperties();
        Instant now = Instant.now();
        when(tokenService.issue(org.mockito.ArgumentMatchers.any())).thenReturn(
                new IssuedServiceToken("write-token", "Bearer", now, now.plusSeconds(60))
        );
        ObjectProvider<ServiceTokenService> tokenServiceProvider = tokenServiceProvider(tokenService);
        RequestInterceptor interceptor = new UserServiceFeignConfiguration()
                .userServiceIdentityInterceptor(tokenServiceProvider, properties);
        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.PUT);
        template.append("/internal/users/10001/profile");

        interceptor.apply(template);

        ArgumentCaptor<ServiceTokenRequest> requestCaptor = ArgumentCaptor.forClass(ServiceTokenRequest.class);
        verify(tokenService).issue(requestCaptor.capture());
        assertThat(requestCaptor.getValue().scopes()).containsExactly("user:write");
    }

    @Test
    void shouldRequestReadScopeForPatchOperation() {
        ServiceTokenService tokenService = mock(ServiceTokenService.class);
        ServiceIdentityProperties properties = new ServiceIdentityProperties();
        Instant now = Instant.now();
        when(tokenService.issue(org.mockito.ArgumentMatchers.any())).thenReturn(
                new IssuedServiceToken("patch-token", "Bearer", now, now.plusSeconds(60))
        );
        ObjectProvider<ServiceTokenService> tokenServiceProvider = tokenServiceProvider(tokenService);
        RequestInterceptor interceptor = new UserServiceFeignConfiguration()
                .userServiceIdentityInterceptor(tokenServiceProvider, properties);
        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.PATCH);
        template.append("/internal/users/10001/username");

        interceptor.apply(template);

        ArgumentCaptor<ServiceTokenRequest> requestCaptor = ArgumentCaptor.forClass(ServiceTokenRequest.class);
        verify(tokenService).issue(requestCaptor.capture());
        assertThat(requestCaptor.getValue().scopes()).containsExactly("user:read");
    }

    @Test
    void shouldRequestWriteScopeForPostOperation() {
        ServiceTokenService tokenService = mock(ServiceTokenService.class);
        ServiceIdentityProperties properties = new ServiceIdentityProperties();
        Instant now = Instant.now();
        when(tokenService.issue(org.mockito.ArgumentMatchers.any())).thenReturn(
                new IssuedServiceToken("post-token", "Bearer", now, now.plusSeconds(60))
        );
        ObjectProvider<ServiceTokenService> tokenServiceProvider = tokenServiceProvider(tokenService);
        RequestInterceptor interceptor = new UserServiceFeignConfiguration()
                .userServiceIdentityInterceptor(tokenServiceProvider, properties);
        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.POST);
        template.append("/internal/users/batch/status");

        interceptor.apply(template);

        ArgumentCaptor<ServiceTokenRequest> requestCaptor = ArgumentCaptor.forClass(ServiceTokenRequest.class);
        verify(tokenService).issue(requestCaptor.capture());
        assertThat(requestCaptor.getValue().scopes()).containsExactly("user:write");
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ServiceTokenService> tokenServiceProvider(ServiceTokenService tokenService) {
        ObjectProvider<ServiceTokenService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(tokenService);
        return provider;
    }
}
