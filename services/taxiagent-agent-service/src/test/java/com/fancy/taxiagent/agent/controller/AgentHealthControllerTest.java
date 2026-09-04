package com.fancy.taxiagent.agent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证 Agent Service 最小运行状态接口。
 */
@WebMvcTest(
        controllers = AgentHealthController.class,
        properties = {
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false"
        }
)
@AutoConfigureMockMvc(addFilters = false)
class AgentHealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldReturnServiceStatus() throws Exception {
        mockMvc.perform(get("/agent/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("taxiagent-agent-service"))
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
