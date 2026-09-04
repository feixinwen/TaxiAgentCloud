package com.fancy.taxiagent.user.config;

import com.fancy.taxiagent.user.filter.RequestTraceFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证 User Service 的公开路径、默认认证规则和内部服务接口隔离策略。
 */
@WebMvcTest(controllers = UserSecurityTestController.class)
@Import({UserSecurityConfiguration.class, UserSecurityFailureHandler.class, RequestTraceFilter.class})
class UserSecurityConfigurationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        Jwt validJwt = Jwt.withTokenValue("valid-token")
                .header("alg", "RS256")
                .subject("50001")
                .claim("role", "USER")
                .claim("token_type", "access")
                .build();
        when(jwtDecoder.decode("valid-token")).thenReturn(validJwt);
        Jwt serviceJwt = Jwt.withTokenValue("service-token")
                .header("alg", "RS256")
                .subject("taxiagent-auth-service")
                .claim("token_type", "service")
                .claim("scope", "user:read user:write")
                .build();
        Jwt readOnlyServiceJwt = Jwt.withTokenValue("read-only-service-token")
                .header("alg", "RS256")
                .subject("taxiagent-auth-service")
                .claim("token_type", "service")
                .claim("scope", "user:read")
                .build();
        when(jwtDecoder.decode("service-token")).thenReturn(serviceJwt);
        when(jwtDecoder.decode("read-only-service-token")).thenReturn(readOnlyServiceJwt);
        when(jwtDecoder.decode("invalid-token")).thenThrow(new BadJwtException("invalid test token"));
    }

    @Test
    void shouldAllowConfirmedPublicPathWithoutToken() throws Exception {
        mockMvc.perform(get("/api/users/ping"))
                .andExpect(status().isOk())
                .andExpect(header().exists(RequestTraceFilter.TRACE_ID_HEADER));
    }

    @Test
    void shouldRejectProtectedPathWithoutToken() throws Exception {
        mockMvc.perform(get("/api/users/private-test"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(RequestTraceFilter.TRACE_ID_HEADER));
    }

    @Test
    void shouldAllowProtectedPathWithValidToken() throws Exception {
        mockMvc.perform(get("/api/users/private-test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRejectProtectedPathWithInvalidToken() throws Exception {
        mockMvc.perform(get("/api/users/private-test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(RequestTraceFilter.TRACE_ID_HEADER));
    }

    @Test
    void shouldDenyInternalPathForUserAccessToken() throws Exception {
        mockMvc.perform(get("/internal/users/private-test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token"))
                .andExpect(status().isForbidden())
                .andExpect(header().exists(RequestTraceFilter.TRACE_ID_HEADER));
    }

    @Test
    void shouldAllowInternalReadAndWriteForAuthorizedAuthService() throws Exception {
        mockMvc.perform(get("/internal/users/private-test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer service-token"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/internal/users/private-test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer service-token"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRejectInternalWriteWithoutWriteScope() throws Exception {
        mockMvc.perform(put("/internal/users/private-test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer read-only-service-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectServiceTokenOnUserBusinessPath() throws Exception {
        mockMvc.perform(get("/api/users/private-test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer service-token"))
                .andExpect(status().isForbidden());
    }
}
