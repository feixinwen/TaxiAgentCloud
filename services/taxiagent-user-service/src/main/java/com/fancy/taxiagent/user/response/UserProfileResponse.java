package com.fancy.taxiagent.user.response;

import com.fancy.taxiagent.user.domain.enums.UserRole;
import com.fancy.taxiagent.user.dto.UserProfileView;

import java.time.LocalDateTime;

/**
 * User Service 内部接口返回的用户业务资料。
 *
 * <p>响应不包含邮箱、密码、Token 等由 Auth Service 拥有的数据。</p>
 *
 * @param userId    跨服务用户唯一标识
 * @param username  用户名
 * @param role      业务角色
 * @param status    业务状态：0-禁用，1-启用
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
 */
public record UserProfileResponse(
        Long userId,
        String username,
        UserRole role,
        Integer status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    /**
     * 从应用层只读视图创建 HTTP 响应。
     *
     * @param view 用户资料视图
     * @return 内部接口响应
     */
    public static UserProfileResponse from(UserProfileView view) {
        return new UserProfileResponse(
                view.userId(),
                view.username(),
                view.role(),
                view.status(),
                view.createdAt(),
                view.updatedAt()
        );
    }
}
