package com.fancy.taxiagent.agent.controller;

import com.fancy.taxiagent.agent.domain.entity.AgentConversation;
import com.fancy.taxiagent.agent.domain.entity.AgentMessage;
import com.fancy.taxiagent.agent.domain.enums.ConversationStatus;
import com.fancy.taxiagent.agent.domain.enums.MessageRole;
import com.fancy.taxiagent.agent.mapper.AgentConversationMapper;
import com.fancy.taxiagent.agent.mapper.AgentMessageMapper;
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
 * 验证消息历史接口的鉴权、归属校验、顺序、分页与 messageId 类型。
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
class AgentMessageHistoryControllerTest {

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
    private AgentMessageMapper messageMapper;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static final LocalDateTime BASE = LocalDateTime.now();

    @BeforeEach
    void setUp() {
        messageMapper.delete(null);
        conversationMapper.delete(null);
        when(jwtDecoder.decode("user-token")).thenReturn(jwt("50001", "USER"));
        when(jwtDecoder.decode("admin-token")).thenReturn(jwt("50000", "ADMIN"));

        conversationMapper.insert(insertConversation("convo-1", 50001L));
        conversationMapper.insert(insertConversation("convo-other", 99999L));
        messageMapper.insert(insertMessage(1L, "convo-1", MessageRole.USER, "你好", 1L));
        messageMapper.insert(insertMessage(2L, "convo-1", MessageRole.ASSISTANT, "您好，请问去哪？", 2L));
        messageMapper.insert(insertMessage(3L, "convo-1", MessageRole.USER, "我去机场", 3L));
    }

    private AgentConversation insertConversation(String conversationId, long userId) {
        AgentConversation entity = new AgentConversation();
        entity.setId(Math.abs((long) conversationId.hashCode()));
        entity.setConversationId(conversationId);
        entity.setUserId(userId);
        entity.setStatus(ConversationStatus.ACTIVE);
        entity.setVersion(0);
        entity.setCreatedAt(BASE);
        entity.setUpdatedAt(BASE);
        return entity;
    }

    private AgentMessage insertMessage(long id, String conversationId, MessageRole role,
                                       String content, long sequenceNo) {
        AgentMessage entity = new AgentMessage();
        entity.setId(id * 10_000_000_000_000L);
        entity.setConversationDbId(Math.abs((long) conversationId.hashCode()));
        entity.setRunDbId(id);
        entity.setRole(role);
        entity.setContent(content);
        entity.setSequenceNo(sequenceNo);
        entity.setCreatedAt(BASE.plusSeconds(sequenceNo));
        return entity;
    }

    @Test
    void shouldReturn401WithoutToken() throws Exception {
        mockMvc.perform(get("/api/agent/conversations/convo-1/messages"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn404ForForeignOrMissingConversation() throws Exception {
        mockMvc.perform(get("/api/agent/conversations/convo-other/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));

        mockMvc.perform(get("/api/agent/conversations/no-such/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnOwnMessagesAscendingWithDefaultWindow() throws Exception {
        mockMvc.perform(get("/api/agent/conversations/convo-1/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(30))
                .andExpect(jsonPath("$.records.length()").value(3))
                .andExpect(jsonPath("$.records[0].role").value("USER"))
                .andExpect(jsonPath("$.records[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.records[2].content").value("我去机场"))
                // Snowflake ID 必须是 JSON 字符串，不能被当成 number
                .andExpect(jsonPath("$.records[0].messageId").isString());
    }

    @Test
    void shouldPaginateSecondPageAscending() throws Exception {
        mockMvc.perform(get("/api/agent/conversations/convo-1/messages?page=2&size=2")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].sequenceNo").value(3));
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
