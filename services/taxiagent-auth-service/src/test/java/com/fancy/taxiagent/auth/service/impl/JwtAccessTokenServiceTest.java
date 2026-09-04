package com.fancy.taxiagent.auth.service.impl;

import com.fancy.taxiagent.auth.config.JwtProperties;
import com.fancy.taxiagent.auth.service.dto.AccessTokenSubject;
import com.fancy.taxiagent.auth.service.dto.IssuedAccessToken;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 Auth Service 签发 JWT 时的签名、必要声明、有效期与输入约束。
 */
class JwtAccessTokenServiceTest {

    private static final Instant FIXED_NOW = Instant.now().minusSeconds(5);

    private JwtProperties properties;
    private NimbusJwtDecoder decoder;
    private JwtAccessTokenService accessTokenService;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();

        properties = new JwtProperties();
        properties.setIssuer("https://auth.taxiagent.internal");
        properties.setAudience("taxiagent-api");
        properties.setAccessTokenTtl(Duration.ofMinutes(15));
        properties.setKeyId("test-key-1");

        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(properties.getKeyId())
                .build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
        decoder = NimbusJwtDecoder.withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        accessTokenService = new JwtAccessTokenService(
                encoder,
                properties,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldIssueSignedAccessTokenWithMinimalClaims() {
        IssuedAccessToken issued = accessTokenService.issue(
                new AccessTokenSubject(30001L, "user", 4L)
        );

        Jwt decoded = decoder.decode(issued.tokenValue());
        assertThat(issued.tokenType()).isEqualTo("Bearer");
        assertThat(issued.issuedAt()).isEqualTo(FIXED_NOW);
        assertThat(issued.expiresAt()).isEqualTo(FIXED_NOW.plus(Duration.ofMinutes(15)));
        assertThat(decoded.getHeaders()).containsEntry("alg", "RS256").containsEntry("kid", "test-key-1");
        assertThat(decoded.getIssuer().toString()).isEqualTo("https://auth.taxiagent.internal");
        assertThat(decoded.getSubject()).isEqualTo("30001");
        assertThat(decoded.getAudience()).containsExactly("taxiagent-api");
        assertThat(decoded.getClaimAsString("role")).isEqualTo("USER");
        assertThat(decoded.<Long>getClaim("token_version")).isEqualTo(4L);
        assertThat(decoded.getClaimAsString("token_type")).isEqualTo("access");
        assertThat(decoded.getClaims()).doesNotContainKeys("email", "password", "username");
    }

    @Test
    void shouldRejectInvalidSubjectBeforeSigning() {
        assertThatThrownBy(() -> accessTokenService.issue(new AccessTokenSubject(0L, "USER", 0L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");

        assertThatThrownBy(() -> accessTokenService.issue(new AccessTokenSubject(30002L, " ", 0L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role");

        assertThatThrownBy(() -> accessTokenService.issue(new AccessTokenSubject(30002L, "USER", -1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokenVersion");
    }

    @Test
    void shouldRejectNonPositiveAccessTokenTtl() {
        properties.setAccessTokenTtl(Duration.ZERO);

        assertThatThrownBy(() -> accessTokenService.issue(new AccessTokenSubject(30003L, "USER", 0L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("有效期");
    }
}
