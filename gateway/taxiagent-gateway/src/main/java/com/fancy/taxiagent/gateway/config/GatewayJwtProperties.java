package com.fancy.taxiagent.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * Gateway 验证 Auth Access Token 所需的公开配置。
 *
 * <p>Gateway 只接收 RSA 公钥，不允许配置或持有 Auth 私钥。</p>
 */
@ConfigurationProperties(prefix = "taxiagent.gateway.jwt")
public class GatewayJwtProperties {

    private String issuer = "https://auth.taxiagent.internal";

    private String audience = "taxiagent-api";

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

    public Resource getPublicKeyLocation() {
        return publicKeyLocation;
    }

    public void setPublicKeyLocation(Resource publicKeyLocation) {
        this.publicKeyLocation = publicKeyLocation;
    }
}
