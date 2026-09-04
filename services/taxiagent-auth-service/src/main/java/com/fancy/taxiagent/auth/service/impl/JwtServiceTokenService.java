package com.fancy.taxiagent.auth.service.impl;

import com.fancy.taxiagent.auth.config.JwtProperties;
import com.fancy.taxiagent.auth.config.ServiceIdentityProperties;
import com.fancy.taxiagent.auth.service.ServiceTokenService;
import com.fancy.taxiagent.auth.service.dto.IssuedServiceToken;
import com.fancy.taxiagent.auth.service.dto.ServiceTokenRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 使用 Auth RSA 私钥签发短期 RS256 服务身份 Token。
 */
@Lazy
@Service
public class JwtServiceTokenService implements ServiceTokenService {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9._:-]+");
    private static final Logger log = LoggerFactory.getLogger(JwtServiceTokenService.class);

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final ServiceIdentityProperties identityProperties;
    private final Clock clock;

    public JwtServiceTokenService(
            JwtEncoder jwtEncoder,
            JwtProperties jwtProperties,
            ServiceIdentityProperties identityProperties,
            Clock clock
    ) {
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
        this.identityProperties = identityProperties;
        this.clock = clock;
    }

    /**
     * 签发只包含服务身份、目标受众和最小 scope 的短期凭证。
     */
    @Override
    public IssuedServiceToken issue(ServiceTokenRequest request) {
        ValidatedRequest validated = validate(request);
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(identityProperties.getTokenTtl());

        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(requireText(jwtProperties.getKeyId(), "JWT keyId"))
                .build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(requireText(jwtProperties.getIssuer(), "JWT issuer"))
                .subject(validated.serviceId())
                .audience(List.of(validated.audience()))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("token_type", "service")
                .claim("scope", String.join(" ", validated.scopes()))
                .build();

        String tokenValue = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        log.debug(
                "event=service_token_issued serviceId={} audience={} scopes={} expiresAt={}",
                validated.serviceId(),
                validated.audience(),
                validated.scopes(),
                expiresAt
        );
        return new IssuedServiceToken(tokenValue, "Bearer", issuedAt, expiresAt);
    }

    private ValidatedRequest validate(ServiceTokenRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("服务 Token 请求不能为空");
        }
        String serviceId = requireSafeIdentifier(identityProperties.getServiceId(), "serviceId");
        String audience = requireSafeIdentifier(request.audience(), "audience");
        Duration ttl = identityProperties.getTokenTtl();
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalStateException("服务 Token 有效期必须大于零且不超过5分钟");
        }
        if (request.scopes() == null || request.scopes().isEmpty()) {
            throw new IllegalArgumentException("服务 Token scopes 不能为空");
        }
        Set<String> scopes = new TreeSet<>();
        for (String scope : request.scopes()) {
            scopes.add(requireSafeIdentifier(scope, "scope"));
        }
        return new ValidatedRequest(serviceId, audience, Set.copyOf(scopes));
    }

    private String requireSafeIdentifier(String value, String fieldName) {
        String normalized = requireText(value, fieldName);
        if (!SAFE_IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(fieldName + " 包含非法字符");
        }
        return normalized;
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value.trim();
    }

    private record ValidatedRequest(String serviceId, String audience, Set<String> scopes) {
    }
}
