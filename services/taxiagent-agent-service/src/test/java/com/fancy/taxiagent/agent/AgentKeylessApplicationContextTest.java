package com.fancy.taxiagent.agent;

import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.mapper.AgentConversationMapper;
import com.fancy.taxiagent.agent.model.AgentModelGateway;
import com.fancy.taxiagent.agent.model.ModelRequest;
import com.fancy.taxiagent.agent.model.ModelTurn;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证未配置模型密钥时 Agent Service 仍可启动并提供非模型能力。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.discovery.register-enabled=false",
                "spring.ai.openai.api-key=",
                "taxiagent.agent.model.api-key="
        }
)
@AutoConfigureMockMvc
class AgentKeylessApplicationContextTest {

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

    @Autowired
    private AgentModelGateway agentModelGateway;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        conversationMapper.delete(null);
        when(jwtDecoder.decode("keyless-user-token")).thenReturn(jwt());
    }

    @Test
    void shouldServeHealthWithoutModelKey() throws Exception {
        mockMvc.perform(get("/agent/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void shouldStillEnforceAuthenticationWithoutModelKey() throws Exception {
        mockMvc.perform(post("/api/agent/conversations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void shouldCreateConversationWithoutModelKey() throws Exception {
        mockMvc.perform(post("/api/agent/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer keyless-user-token"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void shouldReturnStableModelUnavailableWithoutModelKey() {
        ModelRequest request = new ModelRequest(
                "system",
                "support-v1",
                List.of(ModelTurn.user("question"))
        );

        assertThatThrownBy(() -> agentModelGateway.stream(
                request, java.util.Set.of(), ignored -> { }, Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(AgentExecutionException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getCode())
                                .isEqualTo("MODEL_UNAVAILABLE"));
    }

    private Jwt jwt() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("50001")
                .audience(List.of("taxiagent-api"))
                .claim("role", "USER")
                .claim("token_type", "access")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build();
    }
}
