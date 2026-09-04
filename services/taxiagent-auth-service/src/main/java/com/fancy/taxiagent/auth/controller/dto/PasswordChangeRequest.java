package com.fancy.taxiagent.auth.controller.dto;

/**
 * 当前用户修改密码请求。
 *
 * @param password 新密码
 */
public record PasswordChangeRequest(String password) {
}
