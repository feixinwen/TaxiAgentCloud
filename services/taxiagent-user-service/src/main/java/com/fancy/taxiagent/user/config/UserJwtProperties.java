package com.fancy.taxiagent.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * User Service 验证 Auth Access Token 所需的公开配置。
 *
 * <p>User Service 只接收 RSA 公钥，不允许配置或持有 Auth 私钥。</p>
 */
@ConfigurationProperties(prefix = "taxiagent.user.jwt")
public class UserJwtProperties {

    private String issuer = "https://auth.taxiagent.internal";

    private String audience = "taxiagent-api";

    private String serviceAudience = "taxiagent-user-service";

    private Resource publicKeyLocation;

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public String getServiceAudience() {
        return serviceAudience;
    }

    public void setServiceAudience(String serviceAudience) {
        this.serviceAudience = serviceAudience;
    }

    public Resource getPublicKeyLocation() {
        return publicKeyLocation;
    }

    public void setPublicKeyLocation(Resource publicKeyLocation) {
        this.publicKeyLocation = publicKeyLocation;
    }
}
