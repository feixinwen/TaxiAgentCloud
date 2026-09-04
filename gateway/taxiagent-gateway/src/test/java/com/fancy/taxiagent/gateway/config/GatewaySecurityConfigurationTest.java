package com.fancy.taxiagent.gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import com.fancy.taxiagent.gateway.filter.RequestTraceFilter;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 验证 Gateway 公开路径、默认认证规则和 Bearer Token 过滤行为。
 */
@WebFluxTest(controllers = GatewaySecurityTestController.class)
@Import({
        GatewaySecurityConfiguration.class,
        GatewaySecurityFailureHandler.class,
        RequestTraceFilter.class
})
class GatewaySecurityConfigurationTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ReactiveJwtDecoder reactiveJwtDecoder;

    // TokenVersionFilter 会被 WebFluxTest 切片自动发现，需要 Redis 模板 mock
    @MockitoBean
    private ReactiveStringRedisTemplate redisTemplate;

    @MockitoBean
    private ReactiveValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(Mono.empty());
        Jwt validJwt = Jwt.withTokenValue("valid-token")
                .header("alg", "RS256")
                .subject("50001")
                .claim("role", "USER")
                .claim("token_type", "access")
                .build();
        when(reactiveJwtDecoder.decode("valid-token")).thenReturn(Mono.just(validJwt));
        when(reactiveJwtDecoder.decode("invalid-token"))
                .thenReturn(Mono.error(new BadJwtException("invalid test token")));
    }

    @Test
    void shouldAllowConfirmedPublicPathsWithoutToken() {
        webTestClient.get().uri("/api/auth/ping")
                .exchange()
                .expectStatus().isOk();

        webTestClient.get().uri("/api/users/ping")
                .exchange()
                .expectStatus().isOk();

        // 白名单放行后进入路由层（WebFluxTest 无该路由处理器）→ 404；未放行则被安全层拦截为 401
        webTestClient.post().uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"a@b.com\"}")
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.post().uri("/api/auth/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"a@b.com\",\"scene\":\"REGISTER\"}")
                .exchange()
                .expectStatus().isNotFound();

        // 登录与令牌生命周期路径白名单放行后进入路由层（WebFluxTest 无该路由处理器）→ 404
        webTestClient.post().uri("/api/auth/login/password")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"login\":\"a@b.com\",\"password\":\"p\"}")
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.post().uri("/api/auth/login/email-code")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"a@b.com\",\"code\":\"123456\"}")
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.post().uri("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"refreshToken\":\"rt\"}")
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.post().uri("/api/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"refreshToken\":\"rt\"}")
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.post().uri("/api/auth/password/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"a@b.com\",\"code\":\"123456\",\"newPassword\":\"n\"}")
                .exchange()
                .expectStatus().isNotFound();

        webTestClient.get().uri("/api/auth/username/available?username=alice")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void shouldRejectProtectedPathWithoutToken() {
        webTestClient.get().uri("/api/users/private-test")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists(RequestTraceFilter.TRACE_ID_HEADER);
    }

    @Test
    void shouldAllowProtectedPathWithValidToken() {
        webTestClient.get().uri("/api/users/private-test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void shouldRejectProtectedPathWithInvalidToken() {
        webTestClient.get().uri("/api/users/private-test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists(RequestTraceFilter.TRACE_ID_HEADER);
    }

    @Test
    void shouldRejectAgentPathWithoutToken() {
        webTestClient.post().uri("/api/agent/conversations")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists(RequestTraceFilter.TRACE_ID_HEADER);
    }

    @Test
    void shouldRejectAgentPathWithInvalidToken() {
        webTestClient.post().uri("/api/agent/conversations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists(RequestTraceFilter.TRACE_ID_HEADER);
    }
}
