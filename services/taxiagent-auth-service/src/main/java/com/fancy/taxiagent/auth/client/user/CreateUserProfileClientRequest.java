package com.fancy.taxiagent.auth.client.user;

/**
 * Auth Service 调用 User Service 创建资料时使用的客户端请求。
 *
 * <p>该对象属于 Auth 的防腐层，只复制稳定 HTTP 契约，不引用 User Service 的实体或源码。</p>
 *
 * @param username 用户名
 * @param role     业务角色文本
 */
public record CreateUserProfileClientRequest(String username, String role) {
}
