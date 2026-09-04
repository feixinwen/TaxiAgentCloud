package com.fancy.taxiagent.agent.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 用户向 Agent 对话发送消息的请求。
 *
 * @param clientMessageId 客户端生成的消息幂等 UUID
 * @param content 去除首尾空白后的消息正文
 */
public record SendMessageRequest(
        @NotNull(message = "clientMessageId 不能为空")
        UUID clientMessageId,

        @NotBlank(message = "消息内容不能为空")
        @Size(max = 2000, message = "消息内容不能超过2000个字符")
        String content
) {

    /**
     * 在 Bean Validation 前规范化正文，使长度约束作用于去空白后的内容。
     */
    public SendMessageRequest {
        if (content != null) {
            content = content.trim();
        }
    }
}
