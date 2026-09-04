package com.fancy.taxiagent.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * User Service 内部接口允许的调用方服务身份配置。
 */
@ConfigurationProperties(prefix = "taxiagent.user.service-identity")
public class UserServiceIdentityProperties {

    private String authServiceId = "taxiagent-auth-service";

    public String getAuthServiceId() {
        return authServiceId;
    }

    public void setAuthServiceId(String authServiceId) {
        this.authServiceId = authServiceId;
    }
}
