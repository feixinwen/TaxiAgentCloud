package com.fancy.taxiagent.user.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;

/**
 * User Service 的无状态资源服务器安全策略。
 *
 * <p>普通业务接口必须通过用户 Access Token 最终验证；内部接口在服务身份能力完成前默认拒绝，
 * 防止普通用户 JWT 被误当作微服务凭证。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(UserServiceIdentityProperties.class)
@EnableMethodSecurity
public class UserSecurityConfiguration {

    @Bean
    SecurityFilterChain userSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder userJwtDecoder,
            UserSecurityFailureHandler securityFailureHandler,
            UserServiceIdentityProperties serviceIdentityProperties
    ) throws Exception {
        String authServiceAuthority = "SERVICE_" + requireText(
                serviceIdentityProperties.getAuthServiceId(),
                "USER_AUTH_SERVICE_ID"
        );
        http
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(securityFailureHandler)
                        .accessDeniedHandler(securityFailureHandler)
                )
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/api/users/ping",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info"
                        )
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/internal/users/**")
                        .hasAuthority("INTERNAL_USER_READ")
                        .requestMatchers(HttpMethod.PATCH, "/internal/users/**")
                        .hasAuthority("INTERNAL_USER_READ")
                        .requestMatchers(HttpMethod.PUT, "/internal/users/**")
                        .hasAuthority("INTERNAL_USER_WRITE")
                        .requestMatchers(HttpMethod.POST, "/internal/users/**")
                        .hasAuthority("INTERNAL_USER_WRITE")
                        .requestMatchers("/internal/**")
                        .denyAll()
                        .anyRequest()
                        .hasAuthority("TOKEN_ACCESS")
                )
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(securityFailureHandler)
                        .accessDeniedHandler(securityFailureHandler)
                        .jwt(jwt -> jwt
                                .decoder(userJwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter(authServiceAuthority))
                        )
                );
        return http.build();
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter(String authServiceAuthority) {
        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(jwtAuthoritiesConverter(authServiceAuthority));
        return authenticationConverter;
    }

    private Converter<Jwt, Collection<GrantedAuthority>> jwtAuthoritiesConverter(String authServiceAuthority) {
        JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();
        return jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            String tokenType = jwt.getClaimAsString("token_type");
            if ("access".equals(tokenType)) {
                authorities.add(new SimpleGrantedAuthority("TOKEN_ACCESS"));
                String role = jwt.getClaimAsString("role");
                if (role != null && !role.isBlank()) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role.trim().toUpperCase(Locale.ROOT)));
                }
            } else if ("service".equals(tokenType)) {
                authorities.add(new SimpleGrantedAuthority("TOKEN_SERVICE"));
                String serviceAuthority = "SERVICE_" + jwt.getSubject();
                authorities.add(new SimpleGrantedAuthority(serviceAuthority));
                Collection<GrantedAuthority> scopeAuthorities = scopeConverter.convert(jwt);
                authorities.addAll(scopeAuthorities);
                if (authServiceAuthority.equals(serviceAuthority)) {
                    if (hasAuthority(scopeAuthorities, "SCOPE_user:read")) {
                        authorities.add(new SimpleGrantedAuthority("INTERNAL_USER_READ"));
                    }
                    if (hasAuthority(scopeAuthorities, "SCOPE_user:write")) {
                        authorities.add(new SimpleGrantedAuthority("INTERNAL_USER_WRITE"));
                    }
                }
            }
            return authorities;
        };
    }

    private boolean hasAuthority(Collection<GrantedAuthority> authorities, String expected) {
        return authorities.stream().anyMatch(authority -> expected.equals(authority.getAuthority()));
    }

    private String requireText(String value, String settingName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(settingName + " 不能为空");
        }
        return value.trim();
    }
}
