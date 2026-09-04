package com.fancy.taxiagent.user.dto;

import com.fancy.taxiagent.user.domain.enums.UserRole;

import java.time.LocalDateTime;

/**
 * 用户资料只读数据传输对象。
 *
 * <p>用于向其他层传递用户资料的必要字段，避免直接暴露可变的持久化实体。</p>
 *
 * @param userId      跨服务用户唯一标识
 * @param username    用户名，同一角色下唯一
 * @param role        用户角色
 * @param status      用户状态：0-禁用，1-启用
 * @param createdAt   创建时间
 * @param updatedAt   最后更新时间
 */
public record UserProfileView(
        Long userId,
        String username,
        UserRole role,
        Integer status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
