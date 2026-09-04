package com.fancy.taxiagent.auth.client.user;

import java.util.List;

/**
 * 用户资料分页响应（User Service 内部契约）。
 */
public record UserProfilePageClientResponse(
        long total,
        List<UserProfileClientResponse> records
) {
}
