package com.fancy.taxiagent.auth.service.dto;

import java.util.Set;

/**
 * Auth Service 签发服务身份 Token 的应用层输入。
 *
 * @param audience Token 唯一目标服务，禁止使用通配受众
 * @param scopes   调用目标服务所需的最小权限集合
 */
public record ServiceTokenRequest(String audience, Set<String> scopes) {
}
