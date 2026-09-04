package com.fancy.taxiagent.auth.service.dto;

/**
 * Auth Service 签发 Access Token 所需的最小身份快照。
 *
 * @param userId 用户稳定标识，必须为正数
 * @param role 登录时从 User Service 获取的业务角色
 * @param tokenVersion Auth Service 当前 Token 版本，必须为非负数
 */
public record AccessTokenSubject(
        Long userId,
        String role,
        Long tokenVersion
) {
}
