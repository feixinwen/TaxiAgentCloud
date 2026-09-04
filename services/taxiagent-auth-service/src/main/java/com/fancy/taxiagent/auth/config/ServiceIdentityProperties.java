package com.fancy.taxiagent.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Auth Service 调用内部微服务时使用的服务身份配置。
 *
 * <p>服务 Token 与用户 Access Token 共享 Auth 签名体系，但使用独立 subject、audience、
 * token_type 和 scope，不能互相替代。</p>
 */
@ConfigurationProperties(prefix = "taxiagent.auth.service-identity")
public class ServiceIdentityProperties {

    private String serviceId = "taxiagent-auth-service";

    private Duration tokenTtl = Duration.ofMinutes(1);

    private String userServiceAudience = "taxiagent-user-service";

    private Set<String> userServiceScopes = new LinkedHashSet<>(Set.of("user:read", "user:write"));

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public Duration getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }

    public String getUserServiceAudience() {
        return userServiceAudience;
    }

    public void setUserServiceAudience(String userServiceAudience) {
        this.userServiceAudience = userServiceAudience;
    }

    public Set<String> getUserServiceScopes() {
        return userServiceScopes;
    }

    public void setUserServiceScopes(Set<String> userServiceScopes) {
        this.userServiceScopes = userServiceScopes;
    }
}
