package com.fancy.taxiagent.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * Rag Service 验证 Auth Access Token 所需的公开配置。
 *
 * <p>Rag Service 只接收 RSA 公钥，不允许配置或持有 Auth 私钥。</p>
 */
@ConfigurationProperties(prefix = "taxiagent.rag.jwt")
public class RagJwtProperties {

    private String issuer = "https://auth.taxiagent.internal";

    private String audience = "taxiagent-api";

    private String serviceAudience = "taxiagent-rag-service";

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
