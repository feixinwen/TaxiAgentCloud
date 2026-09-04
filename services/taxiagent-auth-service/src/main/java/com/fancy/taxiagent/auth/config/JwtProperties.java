package com.fancy.taxiagent.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.time.Duration;

/**
 * Auth Service 的 JWT 签发与验证配置。
 *
 * <p>密钥位置必须由运行环境提供，生产私钥不得打包进应用或提交到代码仓库。</p>
 */
@ConfigurationProperties(prefix = "taxiagent.auth.jwt")
public class JwtProperties {

    private String issuer = "https://auth.taxiagent.internal";

    private String audience = "taxiagent-api";

    private Duration accessTokenTtl = Duration.ofMinutes(15);

    private String keyId = "taxiagent-auth-key";

    private Resource publicKeyLocation;

    private Resource privateKeyLocation;

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

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public void setAccessTokenTtl(Duration accessTokenTtl) {
        this.accessTokenTtl = accessTokenTtl;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public Resource getPublicKeyLocation() {
        return publicKeyLocation;
    }

    public void setPublicKeyLocation(Resource publicKeyLocation) {
        this.publicKeyLocation = publicKeyLocation;
    }

    public Resource getPrivateKeyLocation() {
        return privateKeyLocation;
    }

    public void setPrivateKeyLocation(Resource privateKeyLocation) {
        this.privateKeyLocation = privateKeyLocation;
    }
}
