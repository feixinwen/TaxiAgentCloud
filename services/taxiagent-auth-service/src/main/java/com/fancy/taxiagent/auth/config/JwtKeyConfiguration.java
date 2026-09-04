package com.fancy.taxiagent.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;

/**
 * 加载 Auth RSA 密钥并创建 JWT 编解码组件。
 *
 * <p>密钥 Bean 使用延迟初始化，使不涉及 HTTP 安全或 Token 签发的数据库测试无需接触密钥；
 * 实际启动 Web 服务时仍会校验外部密钥是否存在且格式正确。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({JwtProperties.class, ServiceIdentityProperties.class})
public class JwtKeyConfiguration {

    @Bean
    @Lazy
    RSAPublicKey authJwtPublicKey(JwtProperties properties) {
        return readKey(
                properties.getPublicKeyLocation(),
                RsaKeyConverters.x509(),
                "AUTH_JWT_PUBLIC_KEY_LOCATION"
        );
    }

    @Bean
    @Lazy
    RSAPrivateKey authJwtPrivateKey(JwtProperties properties) {
        return readKey(
                properties.getPrivateKeyLocation(),
                RsaKeyConverters.pkcs8(),
                "AUTH_JWT_PRIVATE_KEY_LOCATION"
        );
    }

    @Bean
    @Lazy
    JwtEncoder jwtEncoder(
            RSAPublicKey authJwtPublicKey,
            RSAPrivateKey authJwtPrivateKey,
            JwtProperties properties
    ) {
        RSAKey rsaKey = new RSAKey.Builder(authJwtPublicKey)
                .privateKey(authJwtPrivateKey)
                .keyID(requireText(properties.getKeyId(), "AUTH_JWT_KEY_ID"))
                .build();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(rsaKey));
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    @Lazy
    JwtDecoder jwtDecoder(RSAPublicKey authJwtPublicKey, JwtProperties properties) {
        String issuer = requireText(properties.getIssuer(), "AUTH_JWT_ISSUER");
        String audience = requireText(properties.getAudience(), "AUTH_JWT_AUDIENCE");

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(authJwtPublicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token",
                        "JWT audience does not match this service",
                        null
                ));
        OAuth2TokenValidator<Jwt> accessTokenValidator = jwt -> "access".equals(jwt.getClaimAsString("token_type"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token",
                        "JWT token_type is not access",
                        null
                ));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                issuerValidator,
                audienceValidator,
                accessTokenValidator
        ));
        return decoder;
    }

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    private <T> T readKey(Resource resource, Converter<InputStream, T> converter, String settingName) {
        if (resource == null) {
            throw new BeanCreationException(settingName + " 未配置，Auth Service 无法安全加载 JWT 密钥");
        }
        try (InputStream inputStream = resource.getInputStream()) {
            T key = converter.convert(inputStream);
            if (key == null) {
                throw new BeanCreationException(settingName + " 中没有可识别的 RSA 密钥");
            }
            return key;
        } catch (IOException | IllegalArgumentException exception) {
            throw new BeanCreationException(settingName + " 指向的 RSA 密钥无法读取或格式错误", exception);
        }
    }

    private String requireText(String value, String settingName) {
        if (value == null || value.isBlank()) {
            throw new BeanCreationException(settingName + " 不能为空");
        }
        return value.trim();
    }
}
