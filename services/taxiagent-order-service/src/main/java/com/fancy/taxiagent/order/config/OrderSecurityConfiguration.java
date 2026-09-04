package com.fancy.taxiagent.order.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;

/**
 * Order Service 的无状态资源服务器安全策略。
 *
 * <p>业务接口必须通过用户 Access Token 最终验证（Gateway 已做入口校验，此处为最终校验）；
 * 角色以 JWT role 声明映射为 ROLE_ 权限，由 @PreAuthorize 在方法级控制。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableMethodSecurity
public class OrderSecurityConfiguration {

    @Bean
    SecurityFilterChain orderSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder orderJwtDecoder,
            OrderSecurityFailureHandler securityFailureHandler
    ) throws Exception {
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
                                "/api/orders/ping",
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info"
                        )
                        .permitAll()
                        .anyRequest()
                        .hasAuthority("TOKEN_ACCESS")
                )
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(securityFailureHandler)
                        .accessDeniedHandler(securityFailureHandler)
                        .jwt(jwt -> jwt
                                .decoder(orderJwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())
                        )
                );
        return http.build();
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(jwtAuthoritiesConverter());
        return authenticationConverter;
    }

    private Converter<Jwt, Collection<GrantedAuthority>> jwtAuthoritiesConverter() {
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
                Collection<GrantedAuthority> scopeAuthorities = scopeConverter.convert(jwt);
                authorities.addAll(scopeAuthorities);
            }
            return authorities;
        };
    }
}
