package com.fancy.taxiagent.user.controller;

import com.fancy.taxiagent.user.config.UserSecurityConfiguration;
import com.fancy.taxiagent.user.config.UserSecurityFailureHandler;
import com.fancy.taxiagent.user.filter.RequestTraceFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = UserHealthController.class,
        properties = {
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false"
        }
)
@Import({UserSecurityConfiguration.class, UserSecurityFailureHandler.class, RequestTraceFilter.class})
class UserHealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void shouldReturnServiceStatus() throws Exception {
        mockMvc.perform(get("/api/users/ping"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .exists(RequestTraceFilter.TRACE_ID_HEADER))
                .andExpect(jsonPath("$.service").value("taxiagent-user-service"))
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
