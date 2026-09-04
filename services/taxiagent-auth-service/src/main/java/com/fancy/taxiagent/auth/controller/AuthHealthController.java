package com.fancy.taxiagent.auth.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auth Service 最小链路验证接口。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthHealthController {

    private static final Logger log = LoggerFactory.getLogger(AuthHealthController.class);

    /**
     * 返回认证服务基础运行状态，用于验证 Gateway、Nacos 和服务实例链路。
     *
     * @return 服务名称与运行状态
     */
    @GetMapping("/ping")
    public PingResponse ping() {
        log.info("event=auth_service_ping status=success");
        return new PingResponse("taxiagent-auth-service", "UP");
    }

    /**
     * Auth Service Ping 接口响应。
     *
     * @param service 服务名称
     * @param status  运行状态
     */
    public record PingResponse(String service, String status) {
    }
}
