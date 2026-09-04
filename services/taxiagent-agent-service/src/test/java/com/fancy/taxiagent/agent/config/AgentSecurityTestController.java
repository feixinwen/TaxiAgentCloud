package com.fancy.taxiagent.agent.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.I_AM_A_TEAPOT;

/**
 * 仅用于验证 Agent SecurityFilterChain 的受保护测试端点。
 */
@RestController
class AgentSecurityTestController {

    @GetMapping("/security-test")
    String protectedEndpoint() {
        return "ok";
    }

    @GetMapping("/security-test/status-error")
    String statusAwareError() {
        throw new ResponseStatusException(I_AM_A_TEAPOT, "test status");
    }
}
