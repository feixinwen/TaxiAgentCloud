package com.fancy.taxiagent.auth.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;

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
 * 验证生产 JWT 解码器会严格检查签名、签发者和受众，并能读取约定格式的外部 RSA 密钥。
 */
class JwtKeyConfigurationTest {

    private JwtEncoder encoder;
    private JwtDecoder decoder;
    private RSAPublicKey publicKey;
    private RSAPrivateKey privateKey;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        publicKey = (RSAPublicKey) keyPair.getPublic();
        privateKey = (RSAPrivateKey) keyPair.getPrivate();

        JwtProperties properties = new JwtProperties();
        properties.setIssuer("https://auth.taxiagent.internal");
        properties.setAudience("taxiagent-api");
        properties.setKeyId("validator-test-key");

        JwtKeyConfiguration configuration = new JwtKeyConfiguration();
        encoder = configuration.jwtEncoder(publicKey, privateKey, properties);
        decoder = configuration.jwtDecoder(publicKey, properties);
    }

    @Test
    void shouldAcceptExpectedIssuerAndAudience() {
        String token = encode("https://auth.taxiagent.internal", "taxiagent-api");

        assertThat(decoder.decode(token).getSubject()).isEqualTo("40001");
    }

    @Test
    void shouldRejectUnexpectedIssuerOrAudience() {
        String wrongIssuer = encode("https://another-issuer.example", "taxiagent-api");
        String wrongAudience = encode("https://auth.taxiagent.internal", "another-api");

        assertThatThrownBy(() -> decoder.decode(wrongIssuer))
                .isInstanceOf(JwtValidationException.class);
        assertThatThrownBy(() -> decoder.decode(wrongAudience))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void shouldRejectNonAccessTokenType() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("https://auth.taxiagent.internal")
                .subject("40001")
                .audience(List.of("taxiagent-api"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("token_type", "service")
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void shouldLoadPkcs8PrivateKeyAndX509PublicKey(@TempDir Path tempDirectory) throws Exception {
        Path privateKeyFile = writePem(tempDirectory, "private.pem", "PRIVATE KEY", privateKey.getEncoded());
        Path publicKeyFile = writePem(tempDirectory, "public.pem", "PUBLIC KEY", publicKey.getEncoded());

        JwtProperties properties = new JwtProperties();
        properties.setPrivateKeyLocation(new FileSystemResource(privateKeyFile));
        properties.setPublicKeyLocation(new FileSystemResource(publicKeyFile));
        JwtKeyConfiguration configuration = new JwtKeyConfiguration();

        assertThat(configuration.authJwtPrivateKey(properties).getModulus()).isEqualTo(privateKey.getModulus());
        assertThat(configuration.authJwtPublicKey(properties).getModulus()).isEqualTo(publicKey.getModulus());
    }

    private String encode(String issuer, String audience) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("40001")
                .audience(List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("token_type", "access")
                .build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private Path writePem(Path directory, String fileName, String label, byte[] encodedKey) throws Exception {
        String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encodedKey);
        String pem = "-----BEGIN " + label + "-----\n"
                + base64
                + "\n-----END " + label + "-----\n";
        return Files.writeString(directory.resolve(fileName), pem, StandardCharsets.US_ASCII);
    }
}
