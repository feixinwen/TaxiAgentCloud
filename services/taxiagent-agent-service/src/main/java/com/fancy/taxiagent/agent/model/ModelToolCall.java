package com.fancy.taxiagent.agent.model;

/**
 * 与具体模型 SDK 解耦的工具调用描述。
 *
 * @param id 模型生成的工具调用标识
 * @param name 工具名称
 * @param arguments JSON 格式的工具参数
 */
public record ModelToolCall(String id, String name, String arguments) {
}
