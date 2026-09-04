package com.fancy.taxiagent.auth.client.user;

import java.time.LocalDateTime;

/**
 * Auth Service 从 User Service 内部接口读取的用户资料快照。
 *
 * <p>该快照仅供注册和登录编排使用，Auth 不把角色、状态等字段持久化到自己的数据库。</p>
 *
 * @param userId    全局用户 ID
 * @param username  用户名
 * @param role      业务角色
 * @param status    用户业务状态
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
 */
public record UserProfileClientResponse(
        Long userId,
        String username,
        String role,
        Integer status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
