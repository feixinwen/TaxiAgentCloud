package com.fancy.taxiagent.auth.service.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理员用户分页视图：User 资料 + Auth 邮箱聚合结果。
 *
 * @param total   符合筛选条件的总记录数
 * @param records 当前页用户行
 */
public record AdminUserPageView(
        long total,
        List<AdminUserRow> records
) {

    /**
     * 单行用户视图；{@code userId} 使用字符串承载雪花 ID，避免前端 JS 精度丢失。
     *
     * @param userId    全局用户 ID（字符串）
     * @param username  用户名
     * @param email     登录邮箱（管理员创建的账号可能无邮箱）
     * @param role      业务角色
     * @param status    用户业务状态
     * @param createdAt 创建时间
     */
    public record AdminUserRow(
            String userId,
            String username,
            String email,
            String role,
            Integer status,
            LocalDateTime createdAt
    ) {
    }
}
