package com.fancy.taxiagent.auth.controller.dto;

/**
 * 管理员用户分页查询请求；分页参数缺省时默认第 1 页、每页 10 条。
 *
 * @param username 用户名模糊关键字，可为 null
 * @param role     角色，可为 null
 * @param deleted  删除状态：null 全部、0 未删除、1 已删除
 * @param pageNum  页码，从 1 开始
 * @param pageSize 每页条数，1~100
 */
public record AdminUserPageRequest(
        String username,
        String role,
        Integer deleted,
        Integer pageNum,
        Integer pageSize
) {
}
