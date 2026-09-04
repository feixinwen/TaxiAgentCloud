package com.fancy.taxiagent.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Gateway 的 Token 吊销检查（登出 jti 黑名单 + token_version 版本号）。
 *
 * <p>在 Spring Security 链（order -100）认证之后运行（本过滤器 order 0）。
 * 自行解码 Bearer Token（复用 {@code gatewayJwtDecoder}，已校验 issuer/audience/token_type），
 * 依次检查：
 * <ol>
 *   <li>jti 黑名单：Redis {@code auth:token_blacklist:{jti}} 存在（登出时写入）→ 401
 *       {@code TOKEN_REVOKED}，使已签发 Access Token 在剩余有效期内立即失效（spec D1）；</li>
 *   <li>token_version：Redis {@code auth:token_version:{sub}} 大于 claim → 401
 *       {@code TOKEN_REVOKED}。</li>
 * </ol>
 * Redis 缺失或异常 → 放行（fail-open，spec D4）并记录 WARN 日志。解码失败或不带
 * Bearer Token → 直接放行，交给安全链处理。</p>
 */
@Component
@Order(0)
public class TokenVersionFilter implements WebFilter {

    private static final String TOKEN_VERSION_PREFIX = "auth:token_version:";
    private static final String TOKEN_BLACKLIST_PREFIX = "auth:token_blacklist:";
    private static final Logger log = LoggerFactory.getLogger(TokenVersionFilter.class);

    private final ReactiveJwtDecoder jwtDecoder;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public TokenVersionFilter(ReactiveJwtDecoder jwtDecoder, ReactiveStringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper) {
        this.jwtDecoder = jwtDecoder;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return chain.filter(exchange);
        }
        String token = authorization.substring(7).trim();
        return jwtDecoder.decode(token)
                .onErrorResume(exception -> chain.filter(exchange).cast(Jwt.class)) // decode failures handled by security chain
                .flatMap(jwt -> checkTokenVersion(exchange, chain, jwt));
    }

    private Mono<Void> checkTokenVersion(ServerWebExchange exchange, WebFilterChain chain, Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        String jti = jwt.getClaimAsString("jti");
        Number tokenVersion = jwt.getClaim("token_version");
        return checkJtiBlacklist(jti)
                .flatMap(blacklisted -> {
                    if (blacklisted) {
                        return reject(exchange, "登录状态已失效，请重新登录");
                    }
                    if (tokenVersion == null) {
                        return chain.filter(exchange);
                    }
                    String key = TOKEN_VERSION_PREFIX + userId;
                    return redisTemplate.opsForValue().get(key)
                            .onErrorResume(exception -> {
                                log.warn("event=gateway_token_version_check_skipped reason=redis_unavailable userId={}", userId, exception);
                                return Mono.empty();
                            })
                            .defaultIfEmpty("")
                            .flatMap(current -> {
                                if (!current.isEmpty() && Long.parseLong(current) > tokenVersion.longValue()) {
                                    return reject(exchange, "登录状态已失效，请重新登录");
                                }
                                return chain.filter(exchange);
                            });
                });
    }

    /**
     * jti 黑名单检查：登出时 Auth 将 jti 写入 {@code auth:token_blacklist:{jti}}（TTL 为 Access Token
     * 剩余有效期），存在即视为已吊销。jti 缺失或 Redis 异常 → 视为未吊销（fail-open，spec D4）。
     */
    private Mono<Boolean> checkJtiBlacklist(String jti) {
        if (jti == null || jti.isBlank()) {
            return Mono.just(false);
        }
        return redisTemplate.opsForValue().get(TOKEN_BLACKLIST_PREFIX + jti)
                .onErrorResume(exception -> {
                    log.warn("event=gateway_jti_blacklist_check_skipped reason=redis_unavailable jti={}", jti, exception);
                    return Mono.empty();
                })
                .defaultIfEmpty("")
                .map(current -> !current.isEmpty());
    }

    private Mono<Void> reject(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        GatewayApiErrorResponse body = new GatewayApiErrorResponse("TOKEN_REVOKED", message,
                exchange.getRequest().getHeaders().getFirst(RequestTraceFilter.TRACE_ID_HEADER), Instant.now());
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
        } catch (JsonProcessingException exception) {
            log.error("event=gateway_token_version_reject_serialization_failed", exception);
            exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return exchange.getResponse().setComplete();
        }
    }
}
