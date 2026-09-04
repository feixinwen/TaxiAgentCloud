package com.fancy.taxiagent.auth.controller.dto;

/**
 * 当前用户修改请求：至少提供一个字段，未提供的字段保持不变。
 *
 * @param username 新用户名（User Service 侧修改），可为 null
 * @param email    新邮箱（Auth 本地修改），可为 null
 */
public record CurrentUserUpdateRequest(String username, String email) {
}
