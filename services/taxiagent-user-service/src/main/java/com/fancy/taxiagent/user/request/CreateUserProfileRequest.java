package com.fancy.taxiagent.user.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 内部接口创建用户资料的 HTTP 请求。
 *
 * <p>该对象只描述 HTTP 输入；跨服务用户 ID 来自路径参数，邮箱和密码等认证数据不得进入该请求。</p>
 *
 * @param username 用户名，去除首尾空格后不能为空，最大 50 个字符
 * @param role     业务角色文本，不能为空且忽略大小写
 */
public record CreateUserProfileRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(max = 50, message = "用户名长度不能超过50个字符")
        String username,
        @NotBlank(message = "用户角色不能为空")
        @Size(max = 20, message = "用户角色长度不能超过20个字符")
        String role
) {
}
