package com.fancy.taxiagent.agent.lock;

import java.time.Duration;

/**
 * 同一 Agent 对话只允许一个 Run 执行的分布式锁抽象。
 */
public interface ConversationRunLock {

    /**
     * 尝试以 runId 为所有者获取对话运行锁。
     *
     * @param conversationId 对外对话 ID
     * @param runId Run ID
     * @param ttl 锁自动过期时间
     * @return 是否成功获取
     */
    boolean tryAcquire(String conversationId, String runId, Duration ttl);

    /**
     * 仅在 runId 与当前所有者相同时释放锁。
     *
     * @param conversationId 对外对话 ID
     * @param runId Run ID
     */
    void release(String conversationId, String runId);
}
