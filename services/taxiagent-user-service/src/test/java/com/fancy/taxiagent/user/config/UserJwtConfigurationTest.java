package com.fancy.taxiagent.user.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 User Service 只使用公钥，并严格拒绝错误签名、签发者、受众和 Token 类型。
 */
class UserJwtConfigurationTest {

    private UserJwtProperties properties;
    private JwtDecoder decoder;
    private JwtEncoder trustedEncoder;
    private JwtEncoder untrustedEncoder;
    private RSAPublicKey publicKey;

    @BeforeEach
    void setUp() throws Exception {
        KeyPair trustedKeyPair = generateKeyPair();
        KeyPair untrustedKeyPair = generateKeyPair();
        publicKey = (RSAPublicKey) trustedKeyPair.getPublic();

        properties = new UserJwtProperties();
        properties.setIssuer("https://auth.taxiagent.internal");
        properties.setAudience("taxiagent-api");
        properties.setServiceAudience("taxiagent-user-service");

        UserJwtConfiguration configuration = new UserJwtConfiguration();
        decoder = configuration.userJwtDecoder(publicKey, properties);
        trustedEncoder = encoder(trustedKeyPair, "trusted-key");
        untrustedEncoder = encoder(untrustedKeyPair, "untrusted-key");
    }

    @Test
    void shouldAcceptValidAccessToken() {
        String token = encode(
                trustedEncoder,
                "trusted-key",
                "https://auth.taxiagent.internal",
                "taxiagent-api",
                "access"
        );

        assertThat(decoder.decode(token)).isNotNull();
    }

    @Test
    void shouldAcceptServiceTokenOnlyForUserServiceAudience() {
        String token = encode(
                trustedEncoder,
                "trusted-key",
                "https://auth.taxiagent.internal",
                "taxiagent-user-service",
                "service"
        );

        assertThat(decoder.decode(token)).isNotNull();
    }

    @Test
    void shouldRejectWrongSignatureIssuerAudienceAndTokenType() {
        assertRejected(encode(
                untrustedEncoder,
                "untrusted-key",
                "https://auth.taxiagent.internal",
                "taxiagent-api",
                "access"
        ));
        assertRejected(encode(
                trustedEncoder,
                "trusted-key",
                "https://another-issuer.example",
                "taxiagent-api",
                "access"
        ));
        assertRejected(encode(
                trustedEncoder,
                "trusted-key",
                "https://auth.taxiagent.internal",
                "another-api",
                "access"
        ));
        assertRejected(encode(
                trustedEncoder,
                "trusted-key",
                "https://auth.taxiagent.internal",
                "taxiagent-api",
                "service"
        ));
    }

    @Test
    void shouldLoadX509PublicKeyWithoutPrivateKey(@TempDir Path tempDirectory) throws Exception {
        String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(publicKey.getEncoded());
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + base64
                + "\n-----END PUBLIC KEY-----\n";
        Path publicKeyFile = Files.writeString(
                tempDirectory.resolve("auth-jwt-public.pem"),
                pem,
                StandardCharsets.US_ASCII
        );
        properties.setPublicKeyLocation(new FileSystemResource(publicKeyFile));

        RSAPublicKey loaded = new UserJwtConfiguration().userJwtPublicKey(properties);

        assertThat(loaded.getModulus()).isEqualTo(publicKey.getModulus());
    }

    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private JwtEncoder encoder(KeyPair keyPair, String keyId) {
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(keyId)
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
    }

    private String encode(
            JwtEncoder encoder,
            String keyId,
            String issuer,
            String audience,
            String tokenType
    ) {
        Instant now = Instant.now();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("50001")
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("role", "USER")
                .claim("token_version", 0L)
                .claim("token_type", tokenType)
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private void assertRejected(String token) {
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }
}
