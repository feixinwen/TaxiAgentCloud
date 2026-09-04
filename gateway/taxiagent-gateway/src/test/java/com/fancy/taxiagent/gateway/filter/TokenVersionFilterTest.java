package com.fancy.taxiagent.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 验证 Gateway 的 Token 版本吊销检查（TOKEN_REVOKED）。
 */
@WebFluxTest
@Import({TokenVersionFilter.class, RequestTraceFilter.class})
class TokenVersionFilterTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    @MockitoBean
    private ReactiveStringRedisTemplate redisTemplate;

    @MockitoBean
    private ReactiveValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        Jwt jwt = Jwt.withTokenValue("whatever")
                .header("alg", "RS256")
                .subject("50001")
                .claim("role", "USER")
                .claim("token_version", 0)
                .claim("token_type", "access")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .jti("jti-1")
                .build();
        when(jwtDecoder.decode("whatever")).thenReturn(Mono.just(jwt));
        // 默认 jti 不在黑名单；需要黑名单命中的测试单独覆写
        when(valueOperations.get("auth:token_blacklist:jti-1")).thenReturn(Mono.empty());
    }

    @Test
    void shouldPassWhenNoRedisVersionExists() {
        when(valueOperations.get("auth:token_version:50001")).thenReturn(Mono.empty());

        webTestClient.get().uri("/any-path")
                .header(HttpHeaders.AUTHORIZATION, "Bearer whatever")
                .exchange()
                .expectStatus().isNotFound(); // passed the filter (404 = no handler), not 401
    }

    @Test
    void shouldPassWhenVersionMatches() {
        when(valueOperations.get("auth:token_version:50001")).thenReturn(Mono.just("0"));

        webTestClient.get().uri("/any-path")
                .header(HttpHeaders.AUTHORIZATION, "Bearer whatever")
                .exchange()
                .expectStatus().isNotFound();
    }

    /**
     * Spring Boot 3.5 的 @WebFluxTest 不再提供默认 permitAll 安全链；若无任何
     * SecurityWebFilterChain bean，WebFilterChainProxy 会回退到
     * anyExchange().authenticated() + httpBasic() 的默认链并直接 401，
     * TokenVersionFilter（order 0，晚于 -100 的安全链）根本没有机会执行。
     * 这里提供一个全放行的安全链，让切片只聚焦 TokenVersionFilter 本身。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class PermitAllSecurityConfiguration {

        @Bean
        SecurityWebFilterChain permitAllSecurityWebFilterChain(ServerHttpSecurity http) {
            return http
                    .csrf(csrf -> csrf.disable())
                    .httpBasic(httpBasic -> httpBasic.disable())
                    .formLogin(formLogin -> formLogin.disable())
                    .authorizeExchange(exchange -> exchange.anyExchange().permitAll())
                    .build();
        }
    }

    @Test
    void shouldRejectWhenVersionMismatch() {
        when(valueOperations.get("auth:token_version:50001")).thenReturn(Mono.just("2"));

        webTestClient.get().uri("/any-path")
                .header(HttpHeaders.AUTHORIZATION, "Bearer whatever")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody(String.class).consumeWith(result -> {
                    assertThat(result.getResponseBody()).contains("TOKEN_REVOKED");
                });
    }

    @Test
    void shouldPassOnRedisError() {
        when(valueOperations.get("auth:token_version:50001"))
                .thenReturn(Mono.error(new RuntimeException("redis down")));

        webTestClient.get().uri("/any-path")
                .header(HttpHeaders.AUTHORIZATION, "Bearer whatever")
                .exchange()
                .expectStatus().isNotFound(); // fail-open per spec D4
    }

    @Test
    void shouldRejectWhenJtiBlacklistedEvenIfVersionMatches() {
        when(valueOperations.get("auth:token_blacklist:jti-1")).thenReturn(Mono.just("1"));
        when(valueOperations.get("auth:token_version:50001")).thenReturn(Mono.just("0"));

        webTestClient.get().uri("/any-path")
                .header(HttpHeaders.AUTHORIZATION, "Bearer whatever")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody(String.class).consumeWith(result -> {
                    assertThat(result.getResponseBody()).contains("TOKEN_REVOKED");
                });
    }

    @Test
    void shouldPassWhenJtiNotBlacklistedAndVersionMatches() {
        when(valueOperations.get("auth:token_blacklist:jti-1")).thenReturn(Mono.empty());
        when(valueOperations.get("auth:token_version:50001")).thenReturn(Mono.just("0"));

        webTestClient.get().uri("/any-path")
                .header(HttpHeaders.AUTHORIZATION, "Bearer whatever")
                .exchange()
                .expectStatus().isNotFound(); // passed the filter (404 = no handler), not 401
    }
}
