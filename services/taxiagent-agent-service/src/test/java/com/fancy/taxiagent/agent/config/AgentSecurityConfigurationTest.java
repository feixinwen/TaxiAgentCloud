package com.fancy.taxiagent.agent.config;

import com.fancy.taxiagent.agent.filter.RequestTraceFilter;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用真实 PEM 公钥、JWT 解码器和安全过滤链验证 Agent Token 边界。
 */
@WebMvcTest(controllers = AgentSecurityTestController.class)
@Import({
        AgentJwtConfiguration.class,
        AgentSecurityConfiguration.class,
        AgentSecurityFailureHandler.class,
        RequestTraceFilter.class
})
class AgentSecurityConfigurationTest {

    private static final String ISSUER = "https://auth.taxiagent.internal";
    private static final String AUDIENCE = "taxiagent-api";

    private static JwtEncoder jwtEncoder;
    private static Path publicKeyFile;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void createSigningKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAKey rsaKey = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(rsaKey));
        jwtEncoder = new NimbusJwtEncoder(jwkSource);

        publicKeyFile = Files.createTempFile("taxiagent-agent-jwt-", ".pub");
        publicKeyFile.toFile().deleteOnExit();
        String encodedKey = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(publicKey.getEncoded());
        Files.writeString(
                publicKeyFile,
                "-----BEGIN PUBLIC KEY-----\n" + encodedKey + "\n-----END PUBLIC KEY-----\n",
                StandardCharsets.US_ASCII
        );
    }

    @DynamicPropertySource
    static void registerJwtProperties(DynamicPropertyRegistry registry) {
        registry.add("taxiagent.agent.jwt.public-key-location", () -> publicKeyFile.toUri().toString());
        registry.add("taxiagent.agent.jwt.issuer", () -> ISSUER);
        registry.add("taxiagent.agent.jwt.audience", () -> AUDIENCE);
    }

    @Test
    void shouldAllowValidUserAccessTokenThroughRealSecurityChain() throws Exception {
        mockMvc.perform(get("/security-test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(ISSUER, AUDIENCE, "access")))
                .andExpect(status().isOk())
                .andExpect(header().exists(RequestTraceFilter.TRACE_ID_HEADER));
    }

    @ParameterizedTest(name = "reject {0}")
    @MethodSource("invalidTokenPurposes")
    void shouldReturnJson401ForWrongIssuerAudienceOrType(
            String scenario,
            String issuer,
            String audience,
            String tokenType
    ) throws Exception {
        mockMvc.perform(get("/security-test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(issuer, audience, tokenType)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(RequestTraceFilter.TRACE_ID_HEADER))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("请求未通过身份认证"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void shouldFailClosedWhenPublicKeyIsNotConfigured() {
        AgentJwtProperties properties = new AgentJwtProperties();

        assertThatThrownBy(() -> new AgentJwtConfiguration().agentJwtPublicKey(properties))
                .isInstanceOf(BeanCreationException.class)
                .hasMessageContaining("AGENT_JWT_PUBLIC_KEY_LOCATION");
    }

    @Test
    void shouldPreserveStatusAwareErrorsOutsideAgentBusinessControllers() throws Exception {
        mockMvc.perform(get("/security-test/status-error")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(ISSUER, AUDIENCE, "access")))
                .andExpect(status().isIAmATeapot());
    }

    private static Stream<Arguments> invalidTokenPurposes() {
        return Stream.of(
                Arguments.of("wrong issuer", "https://evil.example", AUDIENCE, "access"),
                Arguments.of("wrong audience", ISSUER, "another-api", "access"),
                Arguments.of("wrong token type", ISSUER, AUDIENCE, "service")
        );
    }

    private String token(String issuer, String audience, String tokenType) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .audience(List.of(audience))
                .subject("50001")
                .claim("role", "USER")
                .claim("token_type", tokenType)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(SignatureAlgorithm.RS256).build(),
                claims
        )).getTokenValue();
    }
}
