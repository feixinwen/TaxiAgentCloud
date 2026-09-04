package com.fancy.taxiagent.auth.client.user;

/**
 * 修改用户名请求（User Service 内部契约）。
 */
public record UsernameUpdateClientRequest(String username) {
}
