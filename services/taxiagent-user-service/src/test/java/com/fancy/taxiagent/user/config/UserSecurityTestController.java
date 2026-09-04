package com.fancy.taxiagent.user.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * 仅供 User Service 安全过滤链切片测试使用的端点。
 */
@RestController
public class UserSecurityTestController {

    /**
     * 为公开、业务与内部路径提供相同成功响应，使测试只关注安全策略。
     *
     * @return 固定成功结果
     */
    @GetMapping({"/api/users/ping", "/api/users/private-test", "/internal/users/private-test"})
    public String ok() {
        return "ok";
    }

    /**
     * 为内部写接口的 scope 授权测试提供固定成功响应。
     *
     * @return 固定成功结果
     */
    @PutMapping("/internal/users/private-test")
    public String writeOk() {
        return "ok";
    }
}
