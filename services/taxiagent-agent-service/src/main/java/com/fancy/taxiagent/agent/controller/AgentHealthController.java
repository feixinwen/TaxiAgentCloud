package com.fancy.taxiagent.agent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Agent Service 最小运行状态接口。
 */
@RestController
public class AgentHealthController {

    /**
     * 返回服务运行状态。
     *
     * @return 服务名称与运行状态
     */
    @GetMapping("/agent/health")
    public Map<String, String> health() {
        return Map.of(
                "service", "taxiagent-agent-service",
                "status", "UP"
        );
    }
}
