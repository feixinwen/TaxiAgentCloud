package com.fancy.taxiagent.rag.config;

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
 * Rag Service 的 RSA 公钥加载与 JWT 解码配置。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagJwtProperties.class)
public class RagJwtConfiguration {

    @Bean
    @Lazy
    RSAPublicKey orderJwtPublicKey(RagJwtProperties properties) {
        Resource resource = properties.getPublicKeyLocation();
        if (resource == null) {
            throw new BeanCreationException(
                    "RAG_JWT_PUBLIC_KEY_LOCATION 未配置，Rag Service 无法验证 Auth JWT"
            );
        }
        try (InputStream inputStream = resource.getInputStream()) {
            RSAPublicKey publicKey = RsaKeyConverters.x509().convert(inputStream);
            if (publicKey == null) {
                throw new BeanCreationException("Rag Service JWT 公钥文件中没有可识别的 RSA 公钥");
            }
            return publicKey;
        } catch (IOException | IllegalArgumentException exception) {
            throw new BeanCreationException("Rag Service JWT 公钥无法读取或格式错误", exception);
        }
    }

    @Bean
    @Lazy
    JwtDecoder orderJwtDecoder(RSAPublicKey orderJwtPublicKey, RagJwtProperties properties) {
        String issuer = requireText(properties.getIssuer(), "RAG_JWT_ISSUER");
        String accessAudience = requireText(properties.getAudience(), "RAG_JWT_AUDIENCE");
        String serviceAudience = requireText(properties.getServiceAudience(), "RAG_JWT_SERVICE_AUDIENCE");

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(orderJwtPublicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> tokenPurposeValidator = jwt -> validateTokenPurpose(
                jwt,
                accessAudience,
                serviceAudience
        );
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                issuerValidator,
                tokenPurposeValidator
        ));
        return decoder;
    }

    private OAuth2TokenValidatorResult validateTokenPurpose(
            Jwt jwt,
            String accessAudience,
            String serviceAudience
    ) {
        String tokenType = jwt.getClaimAsString("token_type");
        if ("access".equals(tokenType) && jwt.getAudience().contains(accessAudience)) {
            return OAuth2TokenValidatorResult.success();
        }
        if ("service".equals(tokenType) && jwt.getAudience().contains(serviceAudience)) {
            return OAuth2TokenValidatorResult.success();
        }
        return invalidToken("JWT token_type and audience do not match Rag Service purpose");
    }

    private OAuth2TokenValidatorResult invalidToken(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }

    private String requireText(String value, String settingName) {
        if (value == null || value.isBlank()) {
            throw new BeanCreationException(settingName + " 不能为空");
        }
        return value.trim();
    }
}
