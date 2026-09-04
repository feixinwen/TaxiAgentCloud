package com.fancy.taxiagent.gateway.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 仅供 Gateway 安全过滤链切片测试使用的响应式端点。
 */
@RestController
public class GatewaySecurityTestController {

    /**
     * 为公开与受保护路径提供相同的成功响应，使测试只关注安全决策。
     *
     * @return 固定成功结果
     */
    @GetMapping({"/api/auth/ping", "/api/users/ping", "/api/users/private-test"})
    public Mono<String> ok() {
        return Mono.just("ok");
    }
}
