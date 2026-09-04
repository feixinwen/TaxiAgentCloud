package com.fancy.taxiagent.auth.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fancy.taxiagent.auth.config.AuthSecurityConfiguration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证 Auth Service 最小运行状态接口。
 */
@WebMvcTest(
        controllers = AuthHealthController.class,
        properties = {
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false"
        }
)
@Import(AuthSecurityConfiguration.class)
class AuthHealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void shouldReturnServiceStatus() throws Exception {
        mockMvc.perform(get("/api/auth/ping").header("X-Trace-Id", "auth-test-trace"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "auth-test-trace"))
                .andExpect(jsonPath("$.service").value("taxiagent-auth-service"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void shouldRequireAuthenticationForOtherPaths() throws Exception {
        mockMvc.perform(get("/api/auth/private-test"))
                .andExpect(status().isUnauthorized());
    }
}
