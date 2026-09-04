package com.fancy.taxiagent.auth.service;

import com.fancy.taxiagent.auth.service.dto.IssuedServiceToken;
import com.fancy.taxiagent.auth.service.dto.ServiceTokenRequest;

/**
 * Auth Service 的短期微服务身份 Token 签发接口。
 */
public interface ServiceTokenService {

    /**
     * 为指定下游受众和最小权限集合签发服务 Token。
     *
     * @param request 下游受众与调用所需 scope
     * @return 短期 Bearer 服务 Token
     */
    IssuedServiceToken issue(ServiceTokenRequest request);
}
