package com.fancy.taxiagent.agent.state;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 订单 HITL 状态：槽位、break 标志与防重复下单标记，存 Redis hash agent:order:{conversationId}。
 */
@Service
public class OrderStateService {

    private static final String PREFIX = "agent:order:";
    private static final String BREAK_FIELD = "break";
    private static final String ORDER_ID_FIELD = "orderId";
    private static final String LAST_CONFIRM_TOOL_CALL_ID_FIELD = "lastConfirmToolCallId";
    private static final String[] SLOT_FIELDS = {
            "vehicleType", "isReservation", "isExpedited", "scheduledTime",
            "startAddress", "startLat", "startLng", "endAddress", "endLat", "endLng"
    };
    private static final String[] ESTIMATE_FIELDS = {
            "estTraceId", "estDistance", "estPrice", "estRadio",
            "estVehicleType", "estIsExpedited",
            "estStartLat", "estStartLng", "estEndLat", "estEndLng"
    };
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    public OrderStateService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 写入订单槽位。
     *
     * @param conversationId 对话 ID
     * @param field 槽位字段名
     * @param value 槽位值
     */
    public void setSlot(String conversationId, String field, String value) {
        String key = PREFIX + conversationId;
        redisTemplate.opsForHash().put(key, field, value);
        redisTemplate.expire(key, TTL);
    }

    /**
     * 读取订单槽位。
     *
     * @param conversationId 对话 ID
     * @param field 槽位字段名
     * @return 槽位值；未设置返回 null
     */
    public String getSlot(String conversationId, String field) {
        Object value = redisTemplate.opsForHash().get(PREFIX + conversationId, field);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 全部槽位快照。
     *
     * @param conversationId 对话 ID
     * @return 槽位字段到值的映射
     */
    public Map<String, String> getAllSlots(String conversationId) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(PREFIX + conversationId);
        Map<String, String> slots = new HashMap<>();
        entries.forEach((field, value) -> slots.put(String.valueOf(field), String.valueOf(value)));
        return slots;
    }

    /**
     * 删除全部订单槽位（下单成功后调用，保留 break/orderId 字段；估算快照一并清除）。
     *
     * @param conversationId 对话 ID
     */
    public void deleteSlots(String conversationId) {
        redisTemplate.opsForHash().delete(PREFIX + conversationId, (Object[]) SLOT_FIELDS);
        redisTemplate.opsForHash().delete(PREFIX + conversationId, (Object[]) ESTIMATE_FIELDS);
    }

    /**
     * 保存最近一次价格估算快照（CreateOrderTool 与槽位比对一致后复用轨迹，跳过二次算路）。
     *
     * @param conversationId 对话 ID
     * @param estimate 字段名到值的映射（est* 前缀字段；值为 null 的字段跳过）
     */
    public void setEstimate(String conversationId, Map<String, String> estimate) {
        Map<String, String> writable = new HashMap<>();
        estimate.forEach((field, value) -> {
            if (value != null) {
                writable.put(field, value);
            }
        });
        if (writable.isEmpty()) {
            return;
        }
        String key = PREFIX + conversationId;
        redisTemplate.opsForHash().putAll(key, writable);
        redisTemplate.expire(key, TTL);
    }

    /**
     * 读取最近一次价格估算快照。
     *
     * @param conversationId 对话 ID
     * @return est* 字段到值的映射（只含已保存的字段）
     */
    public Map<String, String> getEstimate(String conversationId) {
        Map<String, String> result = new HashMap<>();
        for (String field : ESTIMATE_FIELDS) {
            String value = getSlot(conversationId, field);
            if (value != null) {
                result.put(field, value);
            }
        }
        return result;
    }

    /**
     * 删除价格估算快照（槽位变化导致快照失效时调用）。
     *
     * @param conversationId 对话 ID
     */
    public void deleteEstimate(String conversationId) {
        redisTemplate.opsForHash().delete(PREFIX + conversationId, (Object[]) ESTIMATE_FIELDS);
    }

    /**
     * 是否处于等待用户确认的 HITL 暂停。
     *
     * @param conversationId 对话 ID
     * @return 暂停中返回 true
     */
    public boolean isBreak(String conversationId) {
        return "yes".equals(getSlot(conversationId, BREAK_FIELD));
    }

    /**
     * 设置或清除 HITL 暂停标志。
     *
     * @param conversationId 对话 ID
     * @param value 暂停中传 true
     */
    public void setBreak(String conversationId, boolean value) {
        setSlot(conversationId, BREAK_FIELD, value ? "yes" : "no");
    }

    /**
     * 是否已创建订单（防重复下单）。
     *
     * @param conversationId 对话 ID
     * @return 已创建返回 true
     */
    public boolean isOrderCreated(String conversationId) {
        return getSlot(conversationId, ORDER_ID_FIELD) != null;
    }

    /**
     * 记录已创建的订单号。
     *
     * @param conversationId 对话 ID
     * @param orderId 订单号
     */
    public void setOrderId(String conversationId, String orderId) {
        setSlot(conversationId, ORDER_ID_FIELD, orderId);
    }

    /**
     * 最近一次 confirmOrder 的工具调用 ID（resume 注入点）。
     *
     * @param conversationId 对话 ID
     * @return 工具调用 ID；无则 null
     */
    public String getLastConfirmToolCallId(String conversationId) {
        return getSlot(conversationId, LAST_CONFIRM_TOOL_CALL_ID_FIELD);
    }

    /**
     * 记录最近一次 confirmOrder 的工具调用 ID。
     *
     * @param conversationId 对话 ID
     * @param toolCallId 工具调用 ID
     */
    public void setLastConfirmToolCallId(String conversationId, String toolCallId) {
        setSlot(conversationId, LAST_CONFIRM_TOOL_CALL_ID_FIELD, toolCallId);
    }
}
