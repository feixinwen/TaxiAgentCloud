package com.fancy.taxiagent.user.dto;

import java.util.List;

/**
 * 用户资料分页视图。
 *
 * @param total   总记录数
 * @param records 当前页记录
 */
public record UserProfilePageView(
        long total,
        List<UserProfileView> records
) {
}
