package com.fancy.taxiagent.agent.config;

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
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;

/**
 * Agent Service 的无状态资源服务器安全策略。
 *
 * <p>Gateway 负责入口初筛，Agent Service 仍会独立完成 Token 最终校验和业务角色授权。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableMethodSecurity
public class AgentSecurityConfiguration {

    /**
     * 配置 Agent Service HTTP 安全过滤链。
     *
     * @param http HTTP 安全构建器
     * @param agentJwtDecoder JWT 解码器
     * @param securityFailureHandler 认证授权失败处理器
     * @return 安全过滤链
     * @throws Exception 配置失败时抛出
     */
    @Bean
    SecurityFilterChain agentSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder agentJwtDecoder,
            AgentSecurityFailureHandler securityFailureHandler
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
                                "/agent/health",
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
                                .decoder(agentJwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())
                        )
                );
        return http.build();
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwtAuthoritiesConverter());
        return converter;
    }

    private Converter<Jwt, Collection<GrantedAuthority>> jwtAuthoritiesConverter() {
        return jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            if (!"access".equals(jwt.getClaimAsString("token_type"))) {
                return authorities;
            }
            authorities.add(new SimpleGrantedAuthority("TOKEN_ACCESS"));
            String role = jwt.getClaimAsString("role");
            if (role != null && !role.isBlank()) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role.trim().toUpperCase(Locale.ROOT)));
            }
            return authorities;
        };
    }
}
