package com.fancy.taxiagent.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 独立于 Web 上下文的密码编码器配置。
 *
 * <p>Auth 服务的业务逻辑（注册、登录、改密）在非 Web 集成测试中也依赖
 * {@link PasswordEncoder}，而 {@link AuthSecurityConfiguration} 带
 * {@code @ConditionalOnWebApplication(SERVLET)}，Web 环境为 NONE 时不会创建。
 * 因此单独放置，保证所有上下文可用。</p>
 */
@Configuration(proxyBeanMethods = false)
public class PasswordEncoderConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
