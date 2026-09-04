package com.fancy.taxiagent.agent.router;

import com.fancy.taxiagent.agent.classify.ClassifierService;
import com.fancy.taxiagent.agent.exception.AgentExecutionException;
import com.fancy.taxiagent.agent.state.ConversationStateService;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 意图路由：分类 → 危险拦截 → ORDER 粘滞 → 未知标签防御 → 分类写回 → 返回目标 Agent 决策。
 *
 * <p>与单体路由规则保持一致：分类不可用、未知标签或 DANGER 返回固定话术失败决策（由
 * AgentService 发消息并完成 Run，不落库）；上次分类为 ORDER 且本次非 ORDER 时强制路由
 * OrderAgent 且不覆盖 Redis 分类。</p>
 */
@Service
public class IntentRouter {

    /** 五类合法意图标签；DANGER 已在路由前单独拦截，此处覆盖全集以拦截 null/未知标签。 */
    private static final Set<String> LABELS = Set.of("ORDER", "DAILY", "SUPPORT", "OTHER", "DANGER");

    private final ClassifierService classifierService;
    private final ConversationStateService stateService;

    public IntentRouter(ClassifierService classifierService, ConversationStateService stateService) {
        this.classifierService = classifierService;
        this.stateService = stateService;
    }

    /**
     * 路由一次用户消息，返回决策供 AgentService 执行。
     *
     * @param conversationId 对外对话 ID
     * @param lastAssistantText 最近一条助手回复（无则 null/空）
     * @param userMessage 当前用户消息
     * @return 路由决策（failure / order_sticky / agent 三种）
     */
    public RouteDecision route(String conversationId, String lastAssistantText, String userMessage) {
        String lastClassification = stateService.getClassification(conversationId);
        String classification;
        try {
            classification = classifierService.classify(lastClassification, lastAssistantText, userMessage);
        } catch (AgentExecutionException e) {
            return RouteDecision.failure("路由失败，请换种方式问问题。");
        }
        if ("DANGER".equals(classification)) {
            return RouteDecision.failure("您的命令不被支持，请换个内容继续吧。");
        }
        // ORDER 粘滞：上次 ORDER 且本次非 ORDER → 强制 OrderAgent，不覆盖
        if ("ORDER".equals(lastClassification) && !"ORDER".equals(classification)) {
            return RouteDecision.orderSticky("当前处于下单流程，如果您想换个话题，请新建对话。");
        }
        // 未知标签防御：分类不在五类标签内（含 null）→ 固定话术失败，不落库
        if (!LABELS.contains(classification)) {
            return RouteDecision.failure("路由失败，请换种方式问问题。");
        }
        // 分类写回（含 ORDER）：保证下一条消息 lastClassification 为 ORDER 时 ORDER 粘滞分支可达
        stateService.setClassification(conversationId, classification);
        return RouteDecision.agent(classification);
    }

    /**
     * 路由决策。
     *
     * @param kind 决策类型：failure（发固定话术并完成 Run，不落库）、order_sticky（发通知后路由 OrderAgent）、agent（按类型路由）
     * @param message failure 或 order_sticky 的提示话术；agent 类型为 null
     * @param agentType 目标 Agent 类型（ORDER/DAILY/SUPPORT/OTHER）
     */
    public record RouteDecision(String kind, String message, String agentType) {

        /**
         * 创建失败决策：AgentService 发固定话术并完成 Run，不落库。
         *
         * @param message 失败提示话术
         * @return 失败决策
         */
        public static RouteDecision failure(String message) {
            return new RouteDecision("failure", message, null);
        }

        /**
         * 创建 ORDER 粘滞决策：AgentService 发通知后路由 OrderAgent。
         *
         * @param message 粘滞提示话术
         * @return 粘滞决策
         */
        public static RouteDecision orderSticky(String message) {
            return new RouteDecision("order_sticky", message, "ORDER");
        }

        /**
         * 创建按类型路由决策。
         *
         * @param agentType 目标 Agent 类型（ORDER/DAILY/SUPPORT/OTHER）
         * @return 路由决策
         */
        public static RouteDecision agent(String agentType) {
            return new RouteDecision("agent", null, agentType);
        }
    }
}
