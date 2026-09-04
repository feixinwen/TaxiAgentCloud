package com.fancy.taxiagent.agent.controller;

import com.fancy.taxiagent.agent.domain.entity.AgentConversation;
import com.fancy.taxiagent.agent.mapper.AgentConversationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证创建 Agent 对话的鉴权、身份归属、响应和持久化行为。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.discovery.register-enabled=false",
                "spring.ai.openai.api-key=test-key"
        }
)
@AutoConfigureMockMvc
class AgentConversationControllerTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("taxiagent_agent")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentConversationMapper conversationMapper;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        conversationMapper.delete(null);
        when(jwtDecoder.decode("user-token")).thenReturn(jwt("50001", "USER"));
        when(jwtDecoder.decode("admin-token")).thenReturn(jwt("50000", "ADMIN"));
        when(jwtDecoder.decode("invalid-subject-token")).thenReturn(jwt("not-a-long", "USER"));
        when(jwtDecoder.decode("zero-subject-token")).thenReturn(jwt("0", "USER"));
    }

    @Test
    void shouldReturn401WithoutToken() throws Exception {
        mockMvc.perform(post("/api/agent/conversations"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void shouldRejectNonUserRole() throws Exception {
        mockMvc.perform(post("/api/agent/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void shouldReturnStable401WhenJwtSubjectIsNotLong() throws Exception {
        mockMvc.perform(post("/api/agent/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-subject-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN_SUBJECT"))
                .andExpect(jsonPath("$.message").value("Token 用户标识不合法"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void shouldReturnStable401WhenJwtSubjectIsNotPositive() throws Exception {
        mockMvc.perform(post("/api/agent/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer zero-subject-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN_SUBJECT"));
    }

    @Test
    void shouldReturnStable405ForUnsupportedMethod() throws Exception {
        mockMvc.perform(patch("/api/agent/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void shouldReturnStable404ForUnknownAgentPath() throws Exception {
        mockMvc.perform(post("/api/agent/not-found")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void shouldCreateActiveConversationForAuthenticatedUser() throws Exception {
        String responseBody = mockMvc.perform(post("/api/agent/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token")
                        .header("X-Trace-Id", "create-conversation-001"))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Trace-Id", "create-conversation-001"))
                .andExpect(jsonPath("$.conversationId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String conversationId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(responseBody)
                .get("conversationId")
                .asText();
        assertThat(UUID.fromString(conversationId).version()).isEqualTo(4);

        List<AgentConversation> conversations = conversationMapper.selectList(null);
        assertThat(conversations).singleElement().satisfies(conversation -> {
            assertThat(conversation.getConversationId()).isEqualTo(conversationId);
            assertThat(conversation.getUserId()).isEqualTo(50001L);
            assertThat(conversation.getStatus().name()).isEqualTo("ACTIVE");
            assertThat(conversation.getCreatedAt()).isNotNull();
        });
    }

    private Jwt jwt(String subject, String role) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .audience(List.of("taxiagent-api"))
                .claim("role", role)
                .claim("token_type", "access")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build();
    }
}
