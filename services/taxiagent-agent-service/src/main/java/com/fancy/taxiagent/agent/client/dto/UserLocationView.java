package com.fancy.taxiagent.agent.client.dto;

/**
 * 用户服务当前位置视图（对齐 user-service UserLocationView 字段，字符串坐标原样透传）。
 */
public record UserLocationView(
        String latitude,
        String longitude,
        String address
) {
}
