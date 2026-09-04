package com.fancy.taxiagent.agent.persistence;

import com.fancy.taxiagent.agent.domain.entity.AgentConversation;
import com.fancy.taxiagent.agent.domain.entity.AgentMessage;
import com.fancy.taxiagent.agent.domain.entity.AgentRun;
import com.fancy.taxiagent.agent.domain.enums.ConversationStatus;
import com.fancy.taxiagent.agent.domain.enums.MessageRole;
import com.fancy.taxiagent.agent.domain.enums.RunStatus;
import com.fancy.taxiagent.agent.mapper.AgentConversationMapper;
import com.fancy.taxiagent.agent.mapper.AgentMessageMapper;
import com.fancy.taxiagent.agent.mapper.AgentRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 MySQL 验证 Agent Service 的 Flyway 脚本、实体映射和关键唯一约束。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "spring.ai.openai.api-key=test-key"
        }
)
class AgentSchemaIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("taxiagent_agent");

    @Autowired
    private AgentConversationMapper conversationMapper;

    @Autowired
    private AgentMessageMapper messageMapper;

    @Autowired
    private AgentRunMapper runMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM agent_run");
        jdbcTemplate.update("DELETE FROM agent_message");
        jdbcTemplate.update("DELETE FROM agent_conversation");
    }

    @Test
    void shouldApplyMigrationAndPersistConversationMessagesAndRun() {
        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1",
                Integer.class
        );
        assertThat(migrationCount).isEqualTo(1);

        AgentConversation conversation = conversation(1001L, "550e8400-e29b-41d4-a716-446655440000");
        assertThat(conversationMapper.insert(conversation)).isEqualTo(1);

        AgentMessage userMessage = message(
                2001L, 1001L, 3001L, "9be45a39-e497-41f4-9538-945c5667c9a1",
                MessageRole.USER, "取消订单会收费吗？", 1L
        );
        AgentMessage assistantMessage = message(
                2002L, 1001L, 3001L, null,
                MessageRole.ASSISTANT, "司机接单后取消可能收费。", 2L
        );
        assertThat(messageMapper.insert(userMessage)).isEqualTo(1);
        assertThat(messageMapper.insert(assistantMessage)).isEqualTo(1);

        AgentRun run = run(3001L, 1001L, 2001L, 2002L);
        assertThat(runMapper.insert(run)).isEqualTo(1);

        AgentConversation savedConversation = conversationMapper.selectById(1001L);
        assertThat(savedConversation.getStatus()).isEqualTo(ConversationStatus.ACTIVE);
        assertThat(savedConversation.getCreatedAt()).isNotNull();
        assertThat(savedConversation.getUpdatedAt()).isNotNull();

        AgentMessage savedMessage = messageMapper.selectById(2001L);
        assertThat(savedMessage.getRole()).isEqualTo(MessageRole.USER);
        assertThat(savedMessage.getSequenceNo()).isEqualTo(1L);

        AgentRun savedRun = runMapper.selectById(3001L);
        assertThat(savedRun.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(savedRun.getAssistantMessageId()).isEqualTo(2002L);
        assertThat(savedRun.getPromptVersion()).isEqualTo("support-v1");
    }

    @Test
    void shouldRejectDuplicateClientMessageWithinConversation() {
        conversationMapper.insert(conversation(1002L, "9cb33cc5-d690-4c36-9cb0-d4be3db22bbb"));
        messageMapper.insert(message(
                2010L, 1002L, 3010L, "d44a9a2b-8015-44e4-b9e9-28d5052a6dab",
                MessageRole.USER, "第一次提交", 1L
        ));

        assertThatThrownBy(() -> messageMapper.insert(message(
                2011L, 1002L, 3011L, "d44a9a2b-8015-44e4-b9e9-28d5052a6dab",
                MessageRole.USER, "重复提交", 2L
        ))).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void shouldRejectDuplicateSequenceWithinConversation() {
        conversationMapper.insert(conversation(1003L, "0cc6026d-02fe-469d-9039-c348b37a430a"));
        messageMapper.insert(message(
                2020L, 1003L, 3020L, "5479f3b8-458b-4656-8353-1434871424f3",
                MessageRole.USER, "用户消息", 1L
        ));

        assertThatThrownBy(() -> messageMapper.insert(message(
                2021L, 1003L, 3020L, null,
                MessageRole.ASSISTANT, "助手消息", 1L
        ))).isInstanceOf(DuplicateKeyException.class);
    }

    private AgentConversation conversation(long id, String conversationId) {
        AgentConversation conversation = new AgentConversation();
        conversation.setId(id);
        conversation.setConversationId(conversationId);
        conversation.setUserId(90001L);
        conversation.setStatus(ConversationStatus.ACTIVE);
        conversation.setVersion(0);
        return conversation;
    }

    private AgentMessage message(long id, long conversationDbId, long runDbId,
                                 String clientMessageId, MessageRole role,
                                 String content, long sequenceNo) {
        AgentMessage message = new AgentMessage();
        message.setId(id);
        message.setConversationDbId(conversationDbId);
        message.setRunDbId(runDbId);
        message.setClientMessageId(clientMessageId);
        message.setRole(role);
        message.setContent(content);
        message.setSequenceNo(sequenceNo);
        return message;
    }

    private AgentRun run(long id, long conversationDbId, long userMessageId, long assistantMessageId) {
        AgentRun run = new AgentRun();
        run.setId(id);
        run.setRunId("a4ae0d79-b3ce-4285-a5fa-66022c11ec13");
        run.setConversationDbId(conversationDbId);
        run.setUserMessageId(userMessageId);
        run.setAssistantMessageId(assistantMessageId);
        run.setAgentType("SUPPORT");
        run.setStatus(RunStatus.COMPLETED);
        run.setModelName("deepseek-chat");
        run.setPromptVersion("support-v1");
        run.setStartedAt(LocalDateTime.now().minusSeconds(1));
        run.setCompletedAt(LocalDateTime.now());
        return run;
    }
}
