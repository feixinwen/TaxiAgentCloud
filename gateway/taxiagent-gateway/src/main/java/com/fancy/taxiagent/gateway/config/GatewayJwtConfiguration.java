package com.fancy.taxiagent.gateway.config;

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
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;

/**
 * Gateway 的 RSA 公钥加载与响应式 JWT 解码配置。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayJwtProperties.class)
public class GatewayJwtConfiguration {

    @Bean
    @Lazy
    RSAPublicKey gatewayJwtPublicKey(GatewayJwtProperties properties) {
        Resource resource = properties.getPublicKeyLocation();
        if (resource == null) {
            throw new BeanCreationException(
                    "GATEWAY_JWT_PUBLIC_KEY_LOCATION 未配置，Gateway 无法验证 Auth JWT"
            );
        }
        try (InputStream inputStream = resource.getInputStream()) {
            RSAPublicKey publicKey = RsaKeyConverters.x509().convert(inputStream);
            if (publicKey == null) {
                throw new BeanCreationException("Gateway JWT 公钥文件中没有可识别的 RSA 公钥");
            }
            return publicKey;
        } catch (IOException | IllegalArgumentException exception) {
            throw new BeanCreationException("Gateway JWT 公钥无法读取或格式错误", exception);
        }
    }

    @Bean
    @Lazy
    ReactiveJwtDecoder gatewayJwtDecoder(
            RSAPublicKey gatewayJwtPublicKey,
            GatewayJwtProperties properties
    ) {
        String issuer = requireText(properties.getIssuer(), "GATEWAY_JWT_ISSUER");
        String audience = requireText(properties.getAudience(), "GATEWAY_JWT_AUDIENCE");

        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withPublicKey(gatewayJwtPublicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : invalidToken("JWT audience does not match Gateway");
        OAuth2TokenValidator<Jwt> accessTokenValidator = jwt -> "access".equals(jwt.getClaimAsString("token_type"))
                ? OAuth2TokenValidatorResult.success()
                : invalidToken("JWT token_type is not access");
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                issuerValidator,
                audienceValidator,
                accessTokenValidator
        ));
        return decoder;
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
