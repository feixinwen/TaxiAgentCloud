package com.fancy.taxiagent.agent.service;

import com.fancy.taxiagent.agent.domain.dto.SendMessageRequest;
import com.fancy.taxiagent.agent.domain.entity.AgentConversation;
import com.fancy.taxiagent.agent.domain.entity.AgentMessage;
import com.fancy.taxiagent.agent.domain.entity.AgentRun;
import com.fancy.taxiagent.agent.domain.enums.ConversationStatus;
import com.fancy.taxiagent.agent.domain.enums.MessageRole;
import com.fancy.taxiagent.agent.domain.enums.RunStatus;
import com.fancy.taxiagent.agent.domain.model.PreparedAgentRun;
import com.fancy.taxiagent.agent.domain.model.SupportAgentResult;
import com.fancy.taxiagent.agent.exception.AgentApiException;
import com.fancy.taxiagent.agent.mapper.AgentConversationMapper;
import com.fancy.taxiagent.agent.mapper.AgentMessageMapper;
import com.fancy.taxiagent.agent.mapper.AgentRunMapper;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 使用真实 MySQL 验证消息准备事务、对话归属、幂等和运行中冲突。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.nacos.discovery.register-enabled=false",
                "spring.ai.openai.api-key=test-key"
        }
)
class AgentRunPersistenceServiceIntegrationTest {

    private static final long USER_ID = 50001L;
    private static final String CONVERSATION_ID = "550e8400-e29b-41d4-a716-446655440000";

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
    private AgentRunPersistenceService persistenceService;

    @Autowired
    private AgentConversationMapper conversationMapper;

    @Autowired
    private AgentMessageMapper messageMapper;

    @Autowired
    private AgentRunMapper runMapper;

    @Autowired
    private Validator validator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        messageMapper.delete(null);
        runMapper.delete(null);
        conversationMapper.delete(null);
    }

    @Test
    void shouldNormalizeAndValidateSendMessageRequest() {
        UUID clientMessageId = UUID.fromString("9be45a39-e497-41f4-9538-945c5667c9a1");
        SendMessageRequest valid = new SendMessageRequest(clientMessageId, "  取消订单会收费吗？  ");
        SendMessageRequest blank = new SendMessageRequest(clientMessageId, "   ");
        SendMessageRequest tooLong = new SendMessageRequest(clientMessageId, "a".repeat(2001));
        SendMessageRequest missingId = new SendMessageRequest(null, "测试");

        assertThat(valid.content()).isEqualTo("取消订单会收费吗？");
        assertThat(validator.validate(valid)).isEmpty();
        assertThat(validator.validate(blank)).isNotEmpty();
        assertThat(validator.validate(tooLong)).isNotEmpty();
        assertThat(validator.validate(missingId)).isNotEmpty();
    }

    @Test
    void shouldInsertUserMessageAndRunningRunInOnePreparation() {
        createConversation(USER_ID, ConversationStatus.ACTIVE);
        UUID clientMessageId = UUID.fromString("9be45a39-e497-41f4-9538-945c5667c9a1");
        String runId = "23f15100-4c80-4ff8-92b1-2e246f770001";

        PreparedAgentRun prepared = persistenceService.prepare(
                USER_ID,
                CONVERSATION_ID,
                clientMessageId,
                "  取消订单会收费吗？  ",
                runId
        );

        List<AgentMessage> messages = messageMapper.selectList(null);
        List<AgentRun> runs = runMapper.selectList(null);
        assertThat(messages).singleElement().satisfies(message -> {
            assertThat(message.getId()).isEqualTo(prepared.userMessageId());
            assertThat(message.getRunDbId()).isEqualTo(prepared.runDbId());
            assertThat(message.getClientMessageId()).isEqualTo(clientMessageId.toString());
            assertThat(message.getRole()).isEqualTo(MessageRole.USER);
            assertThat(message.getContent()).isEqualTo("取消订单会收费吗？");
            assertThat(message.getSequenceNo()).isEqualTo(1L);
        });
        assertThat(runs).singleElement().satisfies(run -> {
            assertThat(run.getId()).isEqualTo(prepared.runDbId());
            assertThat(run.getRunId()).isEqualTo(runId);
            assertThat(run.getUserMessageId()).isEqualTo(prepared.userMessageId());
            assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
            // 插入阶段写占位值，路由后由 AgentService 经 setAgentType 修正
            assertThat(run.getAgentType()).isEqualTo("SUPPORT");
            assertThat(run.getModelName()).isEqualTo("deepseek-chat");
            assertThat(run.getPromptVersion()).isEqualTo("support-v1");
        });
        assertThat(prepared.content()).isEqualTo("取消订单会收费吗？");
        assertThat(prepared.sequenceNo()).isEqualTo(1L);
    }

    @Test
    void shouldUpdateAgentTypeWhileRunIsRunningAndIgnoreAfterTerminal() {
        createConversation(USER_ID, ConversationStatus.ACTIVE);
        PreparedAgentRun prepared = prepare(
                UUID.fromString("9be45a39-e497-41f4-9538-945c5667c9a2"),
                "23f15100-4c80-4ff8-92b1-2e246f770002"
        );

        assertThat(persistenceService.setAgentType(prepared.runDbId(), "DAILY")).isTrue();
        AgentRun updated = runMapper.selectById(prepared.runDbId());
        assertThat(updated.getAgentType()).isEqualTo("DAILY");

        assertThat(persistenceService.complete(prepared, new SupportAgentResult("答复", 1, 0, "support-v1")))
                .isNotZero();
        assertThat(persistenceService.setAgentType(prepared.runDbId(), "ORDER")).isFalse();
        assertThat(runMapper.selectById(prepared.runDbId()).getAgentType()).isEqualTo("DAILY");

        assertThat(persistenceService.setAgentType(prepared.runDbId(), null)).isFalse();
        assertThat(persistenceService.setAgentType(-1L, "ORDER")).isFalse();
    }

    @Test
    void shouldHideForeignConversationAsNotFound() {
        createConversation(90002L, ConversationStatus.ACTIVE);

        assertApiError(
                () -> prepare(UUID.randomUUID(), UUID.randomUUID().toString()),
                404,
                "CONVERSATION_NOT_FOUND"
        );
        assertThat(messageMapper.selectCount(null)).isZero();
        assertThat(runMapper.selectCount(null)).isZero();
    }

    @Test
    void shouldRejectClosedConversationAsNotFound() {
        createConversation(USER_ID, ConversationStatus.CLOSED);

        assertApiError(
                () -> prepare(UUID.randomUUID(), UUID.randomUUID().toString()),
                404,
                "CONVERSATION_NOT_FOUND"
        );
    }

    @Test
    void shouldReturnExistingRunIdForDuplicateClientMessage() {
        createConversation(USER_ID, ConversationStatus.ACTIVE);
        UUID clientMessageId = UUID.randomUUID();
        String existingRunId = UUID.randomUUID().toString();
        persistenceService.prepare(USER_ID, CONVERSATION_ID, clientMessageId, "第一次", existingRunId);

        assertThatThrownBy(() -> prepare(clientMessageId, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(AgentApiException.class, exception -> {
                    assertThat(exception.getStatus().value()).isEqualTo(409);
                    assertThat(exception.getCode()).isEqualTo("DUPLICATE_MESSAGE");
                    assertThat(exception.getRunId()).isEqualTo(existingRunId);
                });
        assertThat(messageMapper.selectCount(null)).isEqualTo(1L);
        assertThat(runMapper.selectCount(null)).isEqualTo(1L);
    }

    @Test
    void shouldRejectSecondMessageWhileConversationHasRunningRun() {
        createConversation(USER_ID, ConversationStatus.ACTIVE);
        persistenceService.prepare(
                USER_ID,
                CONVERSATION_ID,
                UUID.randomUUID(),
                "第一条",
                UUID.randomUUID().toString()
        );

        assertApiError(
                () -> prepare(UUID.randomUUID(), UUID.randomUUID().toString()),
                409,
                "CONVERSATION_BUSY"
        );
        assertThat(messageMapper.selectCount(null)).isEqualTo(1L);
        assertThat(runMapper.selectCount(null)).isEqualTo(1L);
    }

    @Test
    void shouldFailStaleRunningRunAndPrepareNextMessage() {
        createConversation(USER_ID, ConversationStatus.ACTIVE);
        AgentRun stale = insertRunningRun(
                20001L,
                20002L,
                1L,
                LocalDateTime.now().minusSeconds(91)
        );

        PreparedAgentRun recovered = prepare(UUID.randomUUID(), UUID.randomUUID().toString());

        AgentRun failed = runMapper.selectById(stale.getId());
        assertThat(failed.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.getErrorCode()).isEqualTo("RUN_TIMEOUT");
        assertThat(failed.getCompletedAt()).isNotNull();
        assertThat(runMapper.selectById(recovered.runDbId()).getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(recovered.sequenceNo()).isEqualTo(2L);
    }

    @Test
    void shouldKeepRecentRunningRunBusyAtRecoveryBoundary() {
        createConversation(USER_ID, ConversationStatus.ACTIVE);
        AgentRun recent = insertRunningRun(
                20001L,
                20002L,
                1L,
                LocalDateTime.now().minusSeconds(89)
        );

        assertApiError(
                () -> prepare(UUID.randomUUID(), UUID.randomUUID().toString()),
                409,
                "CONVERSATION_BUSY"
        );

        AgentRun unchanged = runMapper.selectById(recent.getId());
        assertThat(unchanged.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(unchanged.getErrorCode()).isNull();
        assertThat(unchanged.getCompletedAt()).isNull();
        assertThat(messageMapper.selectCount(null)).isEqualTo(1L);
    }

    @Test
    void shouldCreateOnlyOneNewRunningRunWhenStaleRecoveryIsConcurrent() throws Exception {
        createConversation(USER_ID, ConversationStatus.ACTIVE);
        AgentRun stale = insertRunningRun(
                20001L,
                20002L,
                1L,
                LocalDateTime.now().minusSeconds(91)
        );
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(() -> prepareAfter(start, UUID.randomUUID()));
            Future<Object> second = executor.submit(() -> prepareAfter(start, UUID.randomUUID()));
            start.countDown();

            List<Object> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );
            assertThat(outcomes).filteredOn(PreparedAgentRun.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(AgentApiException.class::isInstance)
                    .singleElement()
                    .isInstanceOfSatisfying(AgentApiException.class, exception ->
                            assertThat(exception.getCode()).isEqualTo("CONVERSATION_BUSY"));
        }

        assertThat(runMapper.selectById(stale.getId())).satisfies(failed -> {
            assertThat(failed.getStatus()).isEqualTo(RunStatus.FAILED);
            assertThat(failed.getErrorCode()).isEqualTo("RUN_TIMEOUT");
        });
        assertThat(runMapper.selectList(null))
                .filteredOn(run -> run.getStatus() == RunStatus.RUNNING)
                .hasSize(1);
    }

    @Test
    void shouldReturnWinnerRunIdForConcurrentDuplicateClientMessage() throws Exception {
        createConversation(USER_ID, ConversationStatus.ACTIVE);
        UUID clientMessageId = UUID.randomUUID();
        String winnerRunId = UUID.randomUUID().toString();
        CountDownLatch winnerInserted = new CountDownLatch(1);
        CountDownLatch allowWinnerCommit = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> winner = executor.submit(() -> insertWinnerAndHold(
                    clientMessageId,
                    winnerRunId,
                    winnerInserted,
                    allowWinnerCommit
            ));
            assertThat(winnerInserted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Object> contender = executor.submit(() -> prepareResult(clientMessageId));

            try {
                awaitBlockedMessageInsert();
            } finally {
                allowWinnerCommit.countDown();
            }

            winner.get(10, TimeUnit.SECONDS);
            assertThat(contender.get(10, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(AgentApiException.class, duplicate -> {
                        assertThat(duplicate.getCode()).isEqualTo("DUPLICATE_MESSAGE");
                        assertThat(duplicate.getRunId()).isEqualTo(winnerRunId);
                    });
            assertThat(messageMapper.selectCount(null)).isEqualTo(1L);
            assertThat(runMapper.selectCount(null)).isEqualTo(1L);
        }
    }

    @Test
    void shouldRollbackLosingMessageForConcurrentDifferentMessages() throws Exception {
        createConversation(USER_ID, ConversationStatus.ACTIVE);
        UUID winnerClientMessageId = UUID.randomUUID();
        UUID contenderClientMessageId = UUID.randomUUID();
        CountDownLatch winnerInserted = new CountDownLatch(1);
        CountDownLatch allowWinnerCommit = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> winner = executor.submit(() -> insertWinnerAndHold(
                    winnerClientMessageId,
                    UUID.randomUUID().toString(),
                    winnerInserted,
                    allowWinnerCommit
            ));
            assertThat(winnerInserted.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Object> contender = executor.submit(() -> prepareResult(contenderClientMessageId));

            try {
                awaitBlockedMessageInsert();
            } finally {
                allowWinnerCommit.countDown();
            }

            winner.get(10, TimeUnit.SECONDS);
            assertThat(contender.get(10, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(AgentApiException.class, exception ->
                            assertThat(exception.getCode()).isEqualTo("CONVERSATION_BUSY"));
            assertThat(messageMapper.selectCount(null)).isEqualTo(1L);
            assertThat(runMapper.selectCount(null)).isEqualTo(1L);
        }
    }

    @Test
    void shouldAtomicallyCompleteRunAndInsertAssistantMessage() {
        LocalDateTime initialUpdatedAt = LocalDateTime.now().minusHours(1);
        createConversation(USER_ID, ConversationStatus.ACTIVE, initialUpdatedAt);
        PreparedAgentRun prepared = prepare(UUID.randomUUID(), UUID.randomUUID().toString());

        long assistantMessageId = persistenceService.complete(
                prepared,
                new SupportAgentResult("平台规则答复", 2, 1, "support-v1")
        );

        AgentRun completed = runMapper.selectById(prepared.runDbId());
        assertThat(assistantMessageId).isPositive();
        assertThat(completed.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(completed.getAssistantMessageId()).isEqualTo(assistantMessageId);
        assertThat(completed.getCompletedAt()).isNotNull();
        assertThat(completed.getErrorCode()).isNull();
        assertThat(messageMapper.selectList(null))
                .filteredOn(message -> message.getRole() == MessageRole.ASSISTANT)
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.getId()).isEqualTo(assistantMessageId);
                    assertThat(message.getConversationDbId()).isEqualTo(prepared.conversationDbId());
                    assertThat(message.getRunDbId()).isEqualTo(prepared.runDbId());
                    assertThat(message.getContent()).isEqualTo("平台规则答复");
                    assertThat(message.getSequenceNo()).isEqualTo(prepared.sequenceNo() + 1L);
                    assertThat(message.getClientMessageId()).isNull();
                });
        assertThat(conversationMapper.selectById(prepared.conversationDbId()).getUpdatedAt())
                .isAfter(initialUpdatedAt);

        assertThat(persistenceService.fail(prepared, "INTERNAL_ERROR")).isFalse();
        assertThat(persistenceService.cancel(prepared)).isFalse();
        assertThat(runMapper.selectById(prepared.runDbId()).getStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void shouldOnlyFailRunningRunAndPreserveFailedTerminalState() {
        createConversation(USER_ID, ConversationStatus.ACTIVE);
        PreparedAgentRun prepared = prepare(UUID.randomUUID(), UUID.randomUUID().toString());

        assertThat(persistenceService.fail(prepared, "RAG_UNAVAILABLE")).isTrue();
        assertThat(persistenceService.cancel(prepared)).isFalse();
        assertThat(persistenceService.complete(
                prepared,
                new SupportAgentResult("不应保存", 1, 0, "support-v1")
        )).isZero();

        AgentRun failed = runMapper.selectById(prepared.runDbId());
        assertThat(failed.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.getErrorCode()).isEqualTo("RAG_UNAVAILABLE");
        assertThat(failed.getCompletedAt()).isNotNull();
        assertThat(failed.getAssistantMessageId()).isNull();
        assertThat(messageMapper.selectList(null))
                .noneMatch(message -> message.getRole() == MessageRole.ASSISTANT);
    }

    @Test
    void shouldOnlyCancelRunningRunAndPreserveCancelledTerminalState() {
        createConversation(USER_ID, ConversationStatus.ACTIVE);
        PreparedAgentRun prepared = prepare(UUID.randomUUID(), UUID.randomUUID().toString());

        assertThat(persistenceService.cancel(prepared)).isTrue();
        assertThat(persistenceService.fail(prepared, "INTERNAL_ERROR")).isFalse();
        assertThat(persistenceService.cancel(prepared)).isFalse();

        AgentRun cancelled = runMapper.selectById(prepared.runDbId());
        assertThat(cancelled.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(cancelled.getErrorCode()).isNull();
        assertThat(cancelled.getCompletedAt()).isNotNull();
        assertThat(cancelled.getAssistantMessageId()).isNull();
    }

    @Test
    void shouldRollbackRunCompletionWhenAssistantInsertFails() {
        LocalDateTime initialUpdatedAt = LocalDateTime.now().minusHours(1);
        createConversation(USER_ID, ConversationStatus.ACTIVE, initialUpdatedAt);
        PreparedAgentRun prepared = prepare(UUID.randomUUID(), UUID.randomUUID().toString());
        // prepare 的首条消息会写 title，触发 updated_at 列的 ON UPDATE CURRENT_TIMESTAMP，
        // 因此回滚断言的基线必须取 prepare 提交之后、complete 之前的值
        LocalDateTime updatedAtBeforeComplete = conversationMapper.selectById(10001L).getUpdatedAt();
        insertSequenceBlocker(prepared);

        assertThatThrownBy(() -> persistenceService.complete(
                prepared,
                new SupportAgentResult("将因序号冲突回滚", 1, 0, "support-v1")
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        AgentRun running = runMapper.selectById(prepared.runDbId());
        assertThat(running.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(running.getAssistantMessageId()).isNull();
        assertThat(running.getCompletedAt()).isNull();
        assertThat(messageMapper.selectCount(null)).isEqualTo(2L);
        assertThat(conversationMapper.selectById(prepared.conversationDbId()).getUpdatedAt())
                .isEqualTo(updatedAtBeforeComplete);
    }

    private PreparedAgentRun prepare(UUID clientMessageId, String runId) {
        return persistenceService.prepare(
                USER_ID,
                CONVERSATION_ID,
                clientMessageId,
                "测试消息",
                runId
        );
    }

    private Object prepareResult(UUID clientMessageId) {
        try {
            return prepare(clientMessageId, UUID.randomUUID().toString());
        } catch (AgentApiException exception) {
            return exception;
        }
    }

    private Object prepareAfter(CountDownLatch start, UUID clientMessageId) {
        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发恢复开始超时");
            }
            return prepareResult(clientMessageId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待并发恢复开始时被中断", exception);
        }
    }

    private void insertWinnerAndHold(
            UUID clientMessageId,
            String runId,
            CountDownLatch winnerInserted,
            CountDownLatch allowWinnerCommit
    ) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();
            long messageId = 20001L;
            long runDbId = 20002L;

            AgentMessage message = new AgentMessage();
            message.setId(messageId);
            message.setConversationDbId(10001L);
            message.setRunDbId(runDbId);
            message.setClientMessageId(clientMessageId.toString());
            message.setRole(MessageRole.USER);
            message.setContent("胜者消息");
            message.setSequenceNo(1L);
            message.setCreatedAt(now);
            messageMapper.insert(message);

            AgentRun run = new AgentRun();
            run.setId(runDbId);
            run.setRunId(runId);
            run.setConversationDbId(10001L);
            run.setUserMessageId(messageId);
            run.setAgentType("SUPPORT");
            run.setStatus(RunStatus.RUNNING);
            run.setModelName("deepseek-chat");
            run.setPromptVersion("support-v1");
            run.setStartedAt(now);
            run.setCreatedAt(now);
            runMapper.insert(run);

            winnerInserted.countDown();
            try {
                if (!allowWinnerCommit.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("等待竞争事务进入唯一索引阻塞超时");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待胜者事务提交时被中断", exception);
            }
        });
    }

    private void awaitBlockedMessageInsert() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            boolean found = jdbcTemplate.queryForList("SHOW FULL PROCESSLIST").stream()
                    .flatMap(row -> row.values().stream())
                    .filter(value -> value != null)
                    .map(String::valueOf)
                    .map(String::toLowerCase)
                    .anyMatch(value -> value.contains("insert into agent_message"));
            if (found) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("竞争事务未阻塞在 agent_message 唯一索引写入");
    }

    private void createConversation(long userId, ConversationStatus status) {
        createConversation(userId, status, LocalDateTime.now());
    }

    private void createConversation(long userId, ConversationStatus status, LocalDateTime updatedAt) {
        LocalDateTime now = LocalDateTime.now();
        AgentConversation conversation = new AgentConversation();
        conversation.setId(10001L);
        conversation.setConversationId(CONVERSATION_ID);
        conversation.setUserId(userId);
        conversation.setStatus(status);
        conversation.setVersion(0);
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(updatedAt);
        conversationMapper.insert(conversation);
    }

    private void insertSequenceBlocker(PreparedAgentRun prepared) {
        AgentMessage blocker = new AgentMessage();
        blocker.setId(30001L);
        blocker.setConversationDbId(prepared.conversationDbId());
        blocker.setRunDbId(prepared.runDbId());
        blocker.setRole(MessageRole.USER);
        blocker.setContent("用于触发事务回滚的占位消息");
        blocker.setSequenceNo(prepared.sequenceNo() + 1L);
        blocker.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(blocker);
    }

    private AgentRun insertRunningRun(
            long messageId,
            long runDbId,
            long sequenceNo,
            LocalDateTime startedAt
    ) {
        AgentMessage message = new AgentMessage();
        message.setId(messageId);
        message.setConversationDbId(10001L);
        message.setRunDbId(runDbId);
        message.setClientMessageId(UUID.randomUUID().toString());
        message.setRole(MessageRole.USER);
        message.setContent("遗留消息");
        message.setSequenceNo(sequenceNo);
        message.setCreatedAt(startedAt);
        messageMapper.insert(message);

        AgentRun run = new AgentRun();
        run.setId(runDbId);
        run.setRunId(UUID.randomUUID().toString());
        run.setConversationDbId(10001L);
        run.setUserMessageId(messageId);
        run.setAgentType("SUPPORT");
        run.setStatus(RunStatus.RUNNING);
        run.setModelName("deepseek-chat");
        run.setPromptVersion("support-v1");
        run.setStartedAt(startedAt);
        run.setCreatedAt(startedAt);
        runMapper.insert(run);
        return run;
    }

    private void assertApiError(Runnable action, int status, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(AgentApiException.class, exception -> {
                    assertThat(exception.getStatus().value()).isEqualTo(status);
                    assertThat(exception.getCode()).isEqualTo(code);
                });
    }
}
