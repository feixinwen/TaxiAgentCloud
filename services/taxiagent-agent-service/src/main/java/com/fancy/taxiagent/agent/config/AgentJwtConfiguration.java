package com.fancy.taxiagent.agent.config;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;

/**
 * Agent Service 的 RSA 公钥加载与 JWT 解码配置。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentJwtProperties.class)
public class AgentJwtConfiguration {

    /**
     * 从外部资源加载 Auth Service 的 RSA 公钥。
     *
     * @param properties JWT 配置
     * @return RSA 公钥
     */
    @Bean
    @Lazy
    RSAPublicKey agentJwtPublicKey(AgentJwtProperties properties) {
        Resource resource = properties.getPublicKeyLocation();
        if (resource == null) {
            throw new BeanCreationException(
                    "AGENT_JWT_PUBLIC_KEY_LOCATION 未配置，Agent Service 无法验证 Auth JWT"
            );
        }
        try (InputStream inputStream = resource.getInputStream()) {
            RSAPublicKey publicKey = RsaKeyConverters.x509().convert(inputStream);
            if (publicKey == null) {
                throw new BeanCreationException("Agent Service JWT 公钥文件中没有可识别的 RSA 公钥");
            }
            return publicKey;
        } catch (IOException | IllegalArgumentException exception) {
            throw new BeanCreationException("Agent Service JWT 公钥无法读取或格式错误", exception);
        }
    }

    /**
     * 创建仅接受 Agent 用户访问令牌的 JWT 解码器。
     *
     * @param agentJwtPublicKey RSA 公钥
     * @param properties JWT 配置
     * @return JWT 解码器
     */
    @Bean
    @Lazy
    JwtDecoder agentJwtDecoder(RSAPublicKey agentJwtPublicKey, AgentJwtProperties properties) {
        String issuer = requireText(properties.getIssuer(), "AGENT_JWT_ISSUER");
        String audience = requireText(properties.getAudience(), "AGENT_JWT_AUDIENCE");

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(agentJwtPublicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> purposeValidator = jwt -> validateAccessToken(jwt, audience);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerValidator, purposeValidator));
        return decoder;
    }

    private OAuth2TokenValidatorResult validateAccessToken(Jwt jwt, String audience) {
        if ("access".equals(jwt.getClaimAsString("token_type"))
                && jwt.getAudience().contains(audience)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token",
                "JWT token_type and audience do not match Agent Service purpose",
                null
        ));
    }

    private String requireText(String value, String settingName) {
        if (value == null || value.isBlank()) {
            throw new BeanCreationException(settingName + " 不能为空");
        }
        return value.trim();
    }
}
