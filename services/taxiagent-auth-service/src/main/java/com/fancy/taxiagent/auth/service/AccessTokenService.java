package com.fancy.taxiagent.auth.service;

import com.fancy.taxiagent.auth.service.dto.AccessTokenSubject;
import com.fancy.taxiagent.auth.service.dto.IssuedAccessToken;

/**
 * Auth Service 的短期 Access Token 签发接口。
 */
public interface AccessTokenService {

    /**
     * 根据已经完成凭证校验和业务状态校验的身份快照签发 JWT。
     *
     * @param subject 已验证的用户身份、角色和 Token 版本
     * @return 带签发时间和过期时间的 Bearer Token
     */
    IssuedAccessToken issue(AccessTokenSubject subject);
}
