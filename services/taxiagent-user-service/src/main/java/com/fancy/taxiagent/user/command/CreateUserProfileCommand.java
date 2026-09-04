package com.fancy.taxiagent.user.command;

import com.fancy.taxiagent.user.domain.enums.UserRole;

/**
 * 创建用户资料的业务命令。
 *
 * <p>该对象只携带一次创建操作允许输入的数据，不是数据库实体，也不包含业务逻辑。
 * 未来可以由 Auth Service 注册事件、内部接口等不同入口构造，再交给用户资料服务处理。</p>
 *
 * @param userId      由身份服务生成的跨服务用户唯一标识，必须为正整数
 * @param username 用户名，去除首尾空格后不能为空且不能包含 {@code @}；同一角色下必须唯一
 * @param role     用户角色，不能为空
 */
public record CreateUserProfileCommand(
        Long userId,
        String username,
        UserRole role
) {
}
