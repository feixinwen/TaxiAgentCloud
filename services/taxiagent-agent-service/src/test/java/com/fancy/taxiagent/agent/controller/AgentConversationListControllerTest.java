package com.fancy.taxiagent.agent.controller;

import com.fancy.taxiagent.agent.domain.entity.AgentConversation;
import com.fancy.taxiagent.agent.domain.enums.ConversationStatus;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证会话列表接口的鉴权、归属过滤、排序与分页行为。
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
class AgentConversationListControllerTest {

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
        // jwt(...) 辅助方法与打桩方式从 AgentConversationControllerTest 原样复制：
        when(jwtDecoder.decode("user-token")).thenReturn(jwt("50001", "USER"));
        when(jwtDecoder.decode("admin-token")).thenReturn(jwt("50000", "ADMIN"));
    }

    private AgentConversation conversation(long id, String conversationId, long userId,
                                           ConversationStatus status, LocalDateTime updatedAt) {
        AgentConversation entity = new AgentConversation();
        entity.setId(id);
        entity.setConversationId(conversationId);
        entity.setUserId(userId);
        entity.setStatus(status);
        entity.setVersion(0);
        entity.setCreatedAt(updatedAt.minusHours(1));
        entity.setUpdatedAt(updatedAt);
        return entity;
    }

    @Test
    void shouldReturn401WithoutToken() throws Exception {
        mockMvc.perform(get("/api/agent/conversations"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectNonUserRole() throws Exception {
        mockMvc.perform(get("/api/agent/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturnOnlyOwnConversationsOrderedByUpdatedAtDesc() throws Exception {
        LocalDateTime base = LocalDateTime.now();
        AgentConversation old = conversation(1L, "c-old", 50001L, ConversationStatus.ACTIVE, base.minusDays(2));
        old.setTitle("从虹桥火车站到东方明珠");
        conversationMapper.insert(old);
        AgentConversation noTitle = conversation(2L, "c-new", 50001L, ConversationStatus.ACTIVE, base);
        conversationMapper.insert(noTitle);
        conversationMapper.insert(conversation(3L, "c-other-user", 99999L, ConversationStatus.ACTIVE, base));

        mockMvc.perform(get("/api/agent/conversations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.records.length()").value(2))
                .andExpect(jsonPath("$.records[0].conversationId").value("c-new"))
                .andExpect(jsonPath("$.records[0].title").doesNotExist())
                .andExpect(jsonPath("$.records[1].conversationId").value("c-old"))
                .andExpect(jsonPath("$.records[1].title").value("从虹桥火车站到东方明珠"))
                .andExpect(jsonPath("$.records[0].status").value("ACTIVE"));
    }

    @Test
    void shouldPaginateByExplicitPageAndSize() throws Exception {
        LocalDateTime base = LocalDateTime.now();
        for (long i = 1; i <= 25; i++) {
            conversationMapper.insert(conversation(i, "c-" + i, 50001L, ConversationStatus.ACTIVE,
                    base.minusMinutes(26 - i)));
        }

        mockMvc.perform(get("/api/agent/conversations?page=2&size=20")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(25))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.records.length()").value(5))
                .andExpect(jsonPath("$.records[0].conversationId").value("c-5"));
    }

    @Test
    void shouldRejectPageSizeOutOfRange() throws Exception {
        mockMvc.perform(get("/api/agent/conversations?page=0")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));

        mockMvc.perform(get("/api/agent/conversations?size=51")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGINATION"));
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