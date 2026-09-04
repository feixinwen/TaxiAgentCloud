package com.fancy.taxiagent.user.dto;

/**
 * 用户名更新请求。
 *
 * @param username 新用户名
 */
public record UsernameUpdateRequest(
        String username
) {
}
