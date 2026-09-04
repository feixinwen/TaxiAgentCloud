package com.fancy.taxiagent.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fancy.taxiagent.agent.domain.entity.AgentConversation;
import com.fancy.taxiagent.agent.domain.entity.AgentMessage;
import com.fancy.taxiagent.agent.domain.entity.AgentRun;
import com.fancy.taxiagent.agent.config.AgentExecutionProperties;
import com.fancy.taxiagent.agent.domain.enums.ConversationStatus;
import com.fancy.taxiagent.agent.domain.enums.MessageRole;
import com.fancy.taxiagent.agent.domain.enums.RunStatus;
import com.fancy.taxiagent.agent.domain.model.PreparedAgentRun;
import com.fancy.taxiagent.agent.domain.model.SupportAgentResult;
import com.fancy.taxiagent.agent.exception.AgentApiException;
import com.fancy.taxiagent.agent.filter.RequestTraceFilter;
import com.fancy.taxiagent.agent.id.IdGenerator;
import com.fancy.taxiagent.agent.mapper.AgentConversationMapper;
import com.fancy.taxiagent.agent.mapper.AgentMessageMapper;
import com.fancy.taxiagent.agent.mapper.AgentRunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

import com.fancy.taxiagent.agent.model.ModelTurn;

/**
 * 在单个 MySQL 本地事务中准备用户消息与 Agent Run。
 */
@Service
public class AgentRunPersistenceService {

    /**
     * Run 插入时的 agent_type 占位值：路由发生在插入之后的异步执行阶段，
     * 实际 Agent 类型由 AgentService 在路由后通过 {@link #setAgentType} 更新。
     */
    private static final String DEFAULT_AGENT_TYPE = "SUPPORT";
    private static final int TITLE_MAX_LENGTH = 30;
    private static final Logger log = LoggerFactory.getLogger(AgentRunPersistenceService.class);

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentRunMapper runMapper;
    private final IdGenerator idGenerator;
    private final String modelName;
    private final String promptVersion;
    private final AgentExecutionProperties executionProperties;
    private final TransactionTemplate transactionTemplate;

    public AgentRunPersistenceService(
            AgentConversationMapper conversationMapper,
            AgentMessageMapper messageMapper,
            AgentRunMapper runMapper,
            IdGenerator idGenerator,
            PlatformTransactionManager transactionManager,
            AgentExecutionProperties executionProperties,
            @Value("${taxiagent.agent.model.name:deepseek-chat}") String modelName,
            @Value("${taxiagent.agent.model.prompt-version:support-v1}") String promptVersion
    ) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.runMapper = runMapper;
        this.idGenerator = idGenerator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.executionProperties = executionProperties;
        this.modelName = modelName;
        this.promptVersion = promptVersion;
    }

    /**
     * 校验对话归属、幂等和运行状态，并原子插入 USER 消息与 RUNNING Run。
     *
     * @param userId 当前 JWT 用户 ID
     * @param conversationId 对外对话 ID
     * @param clientMessageId 客户端消息幂等 ID
     * @param content 用户消息正文
     * @param runId 服务端生成的 Run ID
     * @return 已提交的运行准备信息
     */
    public PreparedAgentRun prepare(
            long userId,
            String conversationId,
            UUID clientMessageId,
            String content,
            String runId
    ) {
        String normalizedContent = requireMessage(clientMessageId, content);
        requireRunId(runId);
        try {
            return transactionTemplate.execute(status -> doPrepare(
                    userId,
                    conversationId,
                    clientMessageId,
                    normalizedContent,
                    runId
            ));
        } catch (DuplicateKeyException exception) {
            log.warn(
                    "event=agent_run_prepare_conflict traceId={} userId={} conversationId={} clientMessageId={} runId={}",
                    MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY),
                    userId,
                    conversationId,
                    clientMessageId,
                    runId
            );
            Optional<String> concurrentRunId = findExistingRunId(userId, conversationId, clientMessageId);
            if (concurrentRunId.isPresent()) {
                throw duplicateMessage(concurrentRunId.get());
            }
            throw conversationBusy();
        }
    }

    /**
     * 查询当前用户的同一幂等消息是否已经关联 Run。
     *
     * @param userId JWT subject 对应的用户 ID
     * @param conversationId 对外对话 ID
     * @param clientMessageId 客户端消息幂等 ID
     * @return 已存在的对外 Run ID
     */
    public Optional<String> findExistingRunId(long userId, String conversationId, UUID clientMessageId) {
        if (clientMessageId == null) {
            throw invalidMessage();
        }
        AgentConversation conversation = findOwnedActiveConversation(userId, conversationId);
        AgentMessage duplicate = findDuplicateMessage(conversation.getId(), clientMessageId);
        if (duplicate == null) {
            return Optional.empty();
        }
        AgentRun run = findRunForMessage(duplicate.getId());
        return run == null ? Optional.empty() : Optional.of(run.getRunId());
    }

    /**
     * 读取当前用户消息之前最近的用户和助手历史，避免将当前消息重复加入模型上下文。
     *
     * @param conversationDbId 对话内部主键
     * @param beforeSequenceNo 当前用户消息的序号上界（不包含）
     * @param maximumCount 最大历史条数
     * @return 按时间正序排列的模型历史
     */
    public List<ModelTurn> findHistoryBefore(long conversationDbId, long beforeSequenceNo, int maximumCount) {
        if (conversationDbId <= 0 || beforeSequenceNo <= 0 || maximumCount <= 0) {
            return List.of();
        }
        int limit = Math.min(maximumCount, 30);
        List<AgentMessage> descending = messageMapper.selectList(
                new LambdaQueryWrapper<AgentMessage>()
                        .eq(AgentMessage::getConversationDbId, conversationDbId)
                        .lt(AgentMessage::getSequenceNo, beforeSequenceNo)
                        .in(AgentMessage::getRole, MessageRole.USER, MessageRole.ASSISTANT)
                        .orderByDesc(AgentMessage::getSequenceNo)
                        .last("LIMIT " + limit)
        );
        List<ModelTurn> history = new ArrayList<>(descending.size());
        for (int index = descending.size() - 1; index >= 0; index--) {
            AgentMessage message = descending.get(index);
            history.add(message.getRole() == MessageRole.USER
                    ? ModelTurn.user(message.getContent())
                    : ModelTurn.assistant(message.getContent(), List.of()));
        }
        return List.copyOf(history);
    }

    /**
     * 在本地事务中写入助手消息并将仍在运行的 Run 标记为完成。
     *
     * @param prepared 已提交的 Run 准备信息
     * @param result Agent 成功执行结果
     * @return 助手消息内部主键；若 Run 已被取消或终结则返回 0
     */
    public long complete(PreparedAgentRun prepared, SupportAgentResult result) {
        if (prepared == null || result == null) {
            throw new IllegalArgumentException("完成 Run 的参数不能为空");
        }
        Long assistantMessageId = transactionTemplate.execute(status -> doComplete(prepared, result));
        return assistantMessageId == null ? 0L : assistantMessageId;
    }

    /**
     * 在本地事务中将仍在运行的 Run 标记为安全失败。
     *
     * @param prepared 已提交的 Run 准备信息
     * @param errorCode 可公开的稳定错误码
     * @return 当前调用是否成功把 RUNNING Run 转为 FAILED
     */
    public boolean fail(PreparedAgentRun prepared, String errorCode) {
        return markTerminal(prepared, RunStatus.FAILED, requireErrorCode(errorCode));
    }

    /**
     * 在本地事务中将仍在运行的 Run 标记为完成，但不写入助手消息。
     *
     * <p>用于路由失败等固定话术路径：助手回复已直接流向客户端但按规则不落库
     * （不产生 assistant 消息行），Run 仍以 COMPLETED 终结以释放对话占用。</p>
     *
     * @param prepared 已提交的 Run 准备信息
     * @return 当前调用是否成功把 RUNNING Run 转为 COMPLETED
     */
    public boolean completeWithoutMessage(PreparedAgentRun prepared) {
        return markTerminal(prepared, RunStatus.COMPLETED, null);
    }

    /**
     * 在本地事务中将仍在运行的 Run 标记为客户端取消。
     *
     * @param prepared 已提交的 Run 准备信息
     * @return 当前调用是否成功把 RUNNING Run 转为 CANCELLED
     */
    public boolean cancel(PreparedAgentRun prepared) {
        return markTerminal(prepared, RunStatus.CANCELLED, null);
    }

    /**
     * 在 Run 仍为 RUNNING 时将其 agent_type 更新为实际路由的 Agent 类型。
     *
     * <p>插入阶段尚不知道路由结果，写占位值；路由完成后由 AgentService 调用本方法
     * 修正审计/历史表中的 agent_type。Run 若已被终态竞争终结则更新失败（返回 false）。</p>
     *
     * @param runDbId Run 内部主键
     * @param agentType 实际 Agent 类型（ORDER/DAILY/SUPPORT/FALLBACK）
     * @return 是否成功把 RUNNING Run 的 agent_type 更新为给定值
     */
    public boolean setAgentType(long runDbId, String agentType) {
        if (runDbId <= 0 || agentType == null || agentType.isBlank()) {
            return false;
        }
        Integer changed = transactionTemplate.execute(status -> runMapper.update(
                null,
                new LambdaUpdateWrapper<AgentRun>()
                        .eq(AgentRun::getId, runDbId)
                        .eq(AgentRun::getStatus, RunStatus.RUNNING)
                        .set(AgentRun::getAgentType, agentType)));
        return changed != null && changed == 1;
    }

    private Long doComplete(PreparedAgentRun prepared, SupportAgentResult result) {
        long assistantMessageId = idGenerator.nextId();
        LocalDateTime now = LocalDateTime.now();
        int changed = runMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getId, prepared.runDbId())
                .eq(AgentRun::getStatus, RunStatus.RUNNING)
                .set(AgentRun::getStatus, RunStatus.COMPLETED)
                .set(AgentRun::getAssistantMessageId, assistantMessageId)
                .set(AgentRun::getCompletedAt, now));
        if (changed != 1) {
            return null;
        }
        AgentMessage assistantMessage = new AgentMessage();
        assistantMessage.setId(assistantMessageId);
        assistantMessage.setConversationDbId(prepared.conversationDbId());
        assistantMessage.setRunDbId(prepared.runDbId());
        assistantMessage.setRole(MessageRole.ASSISTANT);
        assistantMessage.setContent(result.content() == null ? "" : result.content());
        assistantMessage.setSequenceNo(prepared.sequenceNo() + 1L);
        assistantMessage.setCreatedAt(now);
        messageMapper.insert(assistantMessage);
        conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getId, prepared.conversationDbId())
                .set(AgentConversation::getUpdatedAt, now));
        return assistantMessageId;
    }

    private boolean markTerminal(PreparedAgentRun prepared, RunStatus terminalStatus, String errorCode) {
        if (prepared == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<AgentRun> update = new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getId, prepared.runDbId())
                .eq(AgentRun::getStatus, RunStatus.RUNNING)
                .set(AgentRun::getStatus, terminalStatus)
                .set(AgentRun::getCompletedAt, now);
        if (errorCode != null) {
            update.set(AgentRun::getErrorCode, errorCode);
        }
        Integer changed = transactionTemplate.execute(status -> runMapper.update(null, update));
        return changed != null && changed == 1;
    }

    private String requireErrorCode(String errorCode) {
        if (errorCode == null || errorCode.isBlank() || errorCode.length() > 64) {
            return "AGENT_UNAVAILABLE";
        }
        return errorCode;
    }

    private PreparedAgentRun doPrepare(
            long userId,
            String conversationId,
            UUID clientMessageId,
            String normalizedContent,
            String runId
    ) {
        AgentConversation conversation = findOwnedActiveConversation(userId, conversationId);

        AgentMessage duplicate = findDuplicateMessage(conversation.getId(), clientMessageId);
        if (duplicate != null) {
            throw duplicateMessage(duplicate);
        }
        recoverStaleRunningRuns(conversation.getId());
        if (hasRunningRun(conversation.getId())) {
            throw conversationBusy();
        }

        long messageId = idGenerator.nextId();
        long runDbId = idGenerator.nextId();
        long sequenceNo = nextSequenceNo(conversation.getId());
        LocalDateTime now = LocalDateTime.now();

        AgentMessage message = new AgentMessage();
        message.setId(messageId);
        message.setConversationDbId(conversation.getId());
        message.setRunDbId(runDbId);
        message.setClientMessageId(clientMessageId.toString());
        message.setRole(MessageRole.USER);
        message.setContent(normalizedContent);
        message.setSequenceNo(sequenceNo);
        message.setCreatedAt(now);

        AgentRun run = new AgentRun();
        run.setId(runDbId);
        run.setRunId(runId);
        run.setConversationDbId(conversation.getId());
        run.setUserMessageId(messageId);
        run.setAgentType(DEFAULT_AGENT_TYPE);
        run.setStatus(RunStatus.RUNNING);
        run.setModelName(modelName);
        run.setPromptVersion(promptVersion);
        run.setStartedAt(now);
        run.setCreatedAt(now);

        messageMapper.insert(message);
        runMapper.insert(run);
        setTitleFromFirstMessage(conversation, sequenceNo, normalizedContent);

        log.info(
                "event=agent_run_prepared traceId={} userId={} conversationId={} runId={} sequenceNo={}",
                MDC.get(RequestTraceFilter.TRACE_ID_MDC_KEY),
                userId,
                conversationId,
                runId,
                sequenceNo
        );
        return new PreparedAgentRun(
                conversation.getId(),
                messageId,
                runDbId,
                runId,
                normalizedContent,
                sequenceNo
        );
    }

    private AgentConversation findOwnedActiveConversation(long userId, String conversationId) {
        if (userId <= 0 || conversationId == null || conversationId.isBlank()) {
            throw conversationNotFound();
        }
        AgentConversation conversation = conversationMapper.selectOne(
                new LambdaQueryWrapper<AgentConversation>()
                        .eq(AgentConversation::getConversationId, conversationId)
                        .eq(AgentConversation::getUserId, userId)
                        .eq(AgentConversation::getStatus, ConversationStatus.ACTIVE)
                        .last("LIMIT 1")
        );
        if (conversation == null) {
            throw conversationNotFound();
        }
        return conversation;
    }

    private AgentMessage findDuplicateMessage(long conversationDbId, UUID clientMessageId) {
        return messageMapper.selectOne(
                new LambdaQueryWrapper<AgentMessage>()
                        .eq(AgentMessage::getConversationDbId, conversationDbId)
                        .eq(AgentMessage::getClientMessageId, clientMessageId.toString())
                        .last("LIMIT 1")
        );
    }

    private boolean hasRunningRun(long conversationDbId) {
        return runMapper.selectCount(
                new LambdaQueryWrapper<AgentRun>()
                        .eq(AgentRun::getConversationDbId, conversationDbId)
                        .eq(AgentRun::getStatus, RunStatus.RUNNING)
        ) > 0;
    }

    private void recoverStaleRunningRuns(long conversationDbId) {
        LocalDateTime completedAt = LocalDateTime.now();
        LocalDateTime staleBefore = completedAt.minus(executionProperties.getLockTtl());
        Long staleCount = runMapper.selectCount(new LambdaQueryWrapper<AgentRun>()
                .eq(AgentRun::getConversationDbId, conversationDbId)
                .eq(AgentRun::getStatus, RunStatus.RUNNING)
                .lt(AgentRun::getStartedAt, staleBefore));
        if (staleCount == null || staleCount == 0) {
            return;
        }
        runMapper.update(null, new LambdaUpdateWrapper<AgentRun>()
                .eq(AgentRun::getConversationDbId, conversationDbId)
                .eq(AgentRun::getStatus, RunStatus.RUNNING)
                .lt(AgentRun::getStartedAt, staleBefore)
                .set(AgentRun::getStatus, RunStatus.FAILED)
                .set(AgentRun::getErrorCode, "RUN_TIMEOUT")
                .set(AgentRun::getCompletedAt, completedAt));
    }

    /**
     * 首条用户消息落地时生成对话标题（首条内容截断 30 字符），已有标题不覆盖。
     * 供对话列表展示；失败不影响主流程。
     */
    private void setTitleFromFirstMessage(AgentConversation conversation, long sequenceNo, String content) {
        if (sequenceNo != 1L || conversation.getTitle() != null) {
            return;
        }
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        String title = trimmed.length() <= TITLE_MAX_LENGTH ? trimmed
                : trimmed.substring(0, TITLE_MAX_LENGTH) + "…";
        conversationMapper.update(null, new LambdaUpdateWrapper<AgentConversation>()
                .eq(AgentConversation::getId, conversation.getId())
                .set(AgentConversation::getTitle, title));
        conversation.setTitle(title);
    }

    private long nextSequenceNo(long conversationDbId) {
        AgentMessage latest = messageMapper.selectOne(
                new LambdaQueryWrapper<AgentMessage>()
                        .eq(AgentMessage::getConversationDbId, conversationDbId)
                        .orderByDesc(AgentMessage::getSequenceNo)
                        .last("LIMIT 1")
        );
        return latest == null ? 1L : latest.getSequenceNo() + 1L;
    }

    private String requireMessage(UUID clientMessageId, String content) {
        if (clientMessageId == null || content == null) {
            throw invalidMessage();
        }
        String normalized = content.trim();
        if (normalized.isEmpty() || normalized.length() > 2000) {
            throw invalidMessage();
        }
        return normalized;
    }

    private void requireRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId 不能为空");
        }
    }

    private AgentApiException invalidMessage() {
        return new AgentApiException(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE", "消息内容或标识不合法");
    }

    private AgentApiException conversationNotFound() {
        return new AgentApiException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "对话不存在");
    }

    private AgentApiException conversationBusy() {
        return new AgentApiException(HttpStatus.CONFLICT, "CONVERSATION_BUSY", "对话正在处理中");
    }

    private AgentApiException duplicateMessage(AgentMessage message) {
        AgentRun existingRun = findRunForMessage(message.getId());
        String existingRunId = existingRun == null ? null : existingRun.getRunId();
        return duplicateMessage(existingRunId);
    }

    private AgentRun findRunForMessage(long messageId) {
        return runMapper.selectOne(
                new LambdaQueryWrapper<AgentRun>()
                        .eq(AgentRun::getUserMessageId, messageId)
                        .last("LIMIT 1")
        );
    }

    private AgentApiException duplicateMessage(String existingRunId) {
        return new AgentApiException(
                HttpStatus.CONFLICT,
                "DUPLICATE_MESSAGE",
                "clientMessageId 已处理",
                existingRunId
        );
    }
}
