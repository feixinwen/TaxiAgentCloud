package com.fancy.taxiagent.rag.controller;

import com.fancy.taxiagent.rag.config.RagSecurityConfiguration;
import com.fancy.taxiagent.rag.config.RagSecurityFailureHandler;
import com.fancy.taxiagent.rag.domain.dto.RagSearchResult;
import com.fancy.taxiagent.rag.exception.EmbeddingErrorException;
import com.fancy.taxiagent.rag.exception.InvalidRagRequestException;
import com.fancy.taxiagent.rag.service.RagAdminService;
import com.fancy.taxiagent.rag.service.RagSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RAG 接口的鉴权与错误映射验证（真实 JWT 安全链）。
 */
@WebMvcTest(controllers = {RagAdminController.class, RagSearchController.class, RagHealthController.class})
@Import({RagExceptionHandler.class, RagSecurityConfiguration.class, RagSecurityFailureHandler.class})
class RagControllerMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RagAdminService ragAdminService;

    @MockitoBean
    private RagSearchService ragSearchService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        when(jwtDecoder.decode("user-token")).thenReturn(jwt("90001", "USER"));
        when(jwtDecoder.decode("admin-token")).thenReturn(jwt("50000", "ADMIN"));
    }

    @Test
    void shouldSearchAsLoggedInUser() throws Exception {
        when(ragSearchService.search("发票怎么开", null))
                .thenReturn(List.of(new RagSearchResult("1001", "发票怎么开", "发票：订单详情页申请")));

        mockMvc.perform(post("/api/rag/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"发票怎么开\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].groupId").value("1001"))
                .andExpect(jsonPath("$[0].answer").value("发票：订单详情页申请"));
    }

    @Test
    void shouldReturn400ForEmptyQuestion() throws Exception {
        mockMvc.perform(post("/api/rag/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void shouldReturn502ForEmbeddingError() throws Exception {
        when(ragSearchService.search(any(), any()))
                .thenThrow(new EmbeddingErrorException("embedding 调用失败"));

        mockMvc.perform(post("/api/rag/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"测试\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EMBEDDING_ERROR"));
    }

    @Test
    void shouldAllowAdminAddQa() throws Exception {
        mockMvc.perform(post("/api/rag/admin/add")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questions\":[\"发票怎么开\"],\"answer\":\"订单详情页申请\"}"))
                .andExpect(status().isOk());

        verify(ragAdminService).addQA(eq(List.of("发票怎么开")), eq("订单详情页申请"));
    }

    @Test
    void shouldRejectUserAccessingAdminEndpoint() throws Exception {
        mockMvc.perform(post("/api/rag/admin/add")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"questions\":[\"q\"],\"answer\":\"a\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturn401WithoutToken() throws Exception {
        mockMvc.perform(post("/api/rag/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"测试\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldPing() throws Exception {
        mockMvc.perform(get("/api/rag/ping"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string("pong"));
    }

    private Jwt jwt(String subject, String role) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("role", role)
                .claim("token_type", "access")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
    }
}
