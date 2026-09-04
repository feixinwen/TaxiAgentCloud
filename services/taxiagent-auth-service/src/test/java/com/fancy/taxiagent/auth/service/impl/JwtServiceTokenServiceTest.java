package com.fancy.taxiagent.auth.service.impl;

import com.fancy.taxiagent.auth.config.JwtProperties;
import com.fancy.taxiagent.auth.config.ServiceIdentityProperties;
import com.fancy.taxiagent.auth.service.dto.IssuedServiceToken;
import com.fancy.taxiagent.auth.service.dto.ServiceTokenRequest;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证服务身份 Token 的签名、隔离声明、最短权限和有效期约束。
 */
class JwtServiceTokenServiceTest {

    private static final Instant FIXED_NOW = Instant.now().minusSeconds(5);

    private ServiceIdentityProperties identityProperties;
    private NimbusJwtDecoder decoder;
    private JwtServiceTokenService serviceTokenService;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();

        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setIssuer("https://auth.taxiagent.internal");
        jwtProperties.setKeyId("service-test-key");
        identityProperties = new ServiceIdentityProperties();
        identityProperties.setServiceId("taxiagent-auth-service");
        identityProperties.setTokenTtl(Duration.ofMinutes(1));

        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(jwtProperties.getKeyId())
                .build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
        decoder = NimbusJwtDecoder.withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        serviceTokenService = new JwtServiceTokenService(
                encoder,
                jwtProperties,
                identityProperties,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldIssueAudienceBoundServiceTokenWithScopes() {
        IssuedServiceToken issued = serviceTokenService.issue(new ServiceTokenRequest(
                "taxiagent-user-service",
                Set.of("user:write", "user:read")
        ));

        Jwt decoded = decoder.decode(issued.tokenValue());
        assertThat(issued.tokenType()).isEqualTo("Bearer");
        assertThat(issued.issuedAt()).isEqualTo(FIXED_NOW);
        assertThat(issued.expiresAt()).isEqualTo(FIXED_NOW.plusSeconds(60));
        assertThat(decoded.getHeaders()).containsEntry("alg", "RS256").containsEntry("kid", "service-test-key");
        assertThat(decoded.getSubject()).isEqualTo("taxiagent-auth-service");
        assertThat(decoded.getAudience()).containsExactly("taxiagent-user-service");
        assertThat(decoded.getClaimAsString("token_type")).isEqualTo("service");
        assertThat(decoded.getClaimAsString("scope")).contains("user:read", "user:write");
        assertThat(decoded.getClaims()).doesNotContainKeys("role", "token_version", "email", "username");
    }

    @Test
    void shouldRejectMissingAudienceOrScopes() {
        assertThatThrownBy(() -> serviceTokenService.issue(new ServiceTokenRequest(" ", Set.of("user:read"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("audience");
        assertThatThrownBy(() -> serviceTokenService.issue(new ServiceTokenRequest(
                "taxiagent-user-service",
                Set.of()
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scopes");
    }

    @Test
    void shouldRejectLongLivedServiceToken() {
        identityProperties.setTokenTtl(Duration.ofMinutes(6));

        assertThatThrownBy(() -> serviceTokenService.issue(new ServiceTokenRequest(
                "taxiagent-user-service",
                Set.of("user:read")
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不超过5分钟");
    }
}
