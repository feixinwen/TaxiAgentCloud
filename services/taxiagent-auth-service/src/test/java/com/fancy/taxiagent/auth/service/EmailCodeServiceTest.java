package com.fancy.taxiagent.auth.service;

import com.fancy.taxiagent.auth.domain.enums.EmailScene;
import com.fancy.taxiagent.auth.exception.AuthApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.redis.testcontainers.RedisContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.cloud.discovery.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.cloud.service-registry.auto-registration.enabled=false",
                "taxiagent.auth.jwt.public-key-location=classpath:keys/auth-jwt-public.pem",
                "taxiagent.auth.jwt.private-key-location=classpath:keys/auth-jwt-private.pem",
                "taxiagent.auth.verification-code.ttl-seconds=300",
                "taxiagent.auth.verification-code.cooldown-seconds=60",
                "management.health.mail.enabled=false"
        }
)
class EmailCodeServiceTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("taxiagent_auth");

    @Container
    @ServiceConnection
    static final RedisContainer REDIS = new RedisContainer("redis:7-alpine");

    @Autowired
    private EmailCodeService emailCodeService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private JavaMailSender mailSender;

    @BeforeEach
    void clearRedisState() {
        redisTemplate.delete("auth:email_code:register:a@b.com");
        redisTemplate.delete("auth:email_code:cd:register:a@b.com");
        redisTemplate.delete("auth:email_code:register:alice@example.com");
        redisTemplate.delete("auth:email_code:cd:register:alice@example.com");
    }

    @Test
    void shouldStoreCodeAndSendEmail() {
        emailCodeService.sendCode("Alice@Example.com", EmailScene.REGISTER);

        String stored = redisTemplate.opsForValue().get("auth:email_code:register:alice@example.com");
        assertThat(stored).matches("\\d{6}");
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void shouldRejectSendDuringCooldown() {
        emailCodeService.sendCode("a@b.com", EmailScene.REGISTER);

        assertThatThrownBy(() -> emailCodeService.sendCode("a@b.com", EmailScene.REGISTER))
                .isInstanceOf(AuthApiException.class)
                .hasFieldOrPropertyWithValue("code", "EMAIL_CODE_COOLDOWN");
    }

    @Test
    void shouldVerifyWithoutConsuming() {
        emailCodeService.sendCode("a@b.com", EmailScene.REGISTER);
        String code = redisTemplate.opsForValue().get("auth:email_code:register:a@b.com");

        assertThat(emailCodeService.verify("a@b.com", EmailScene.REGISTER, code)).isTrue();
        assertThat(redisTemplate.hasKey("auth:email_code:register:a@b.com")).isTrue();
    }

    @Test
    void shouldConsumeCodeOnce() {
        emailCodeService.sendCode("a@b.com", EmailScene.REGISTER);
        String code = redisTemplate.opsForValue().get("auth:email_code:register:a@b.com");

        assertThat(emailCodeService.verifyAndConsume("a@b.com", EmailScene.REGISTER, code)).isTrue();
        assertThat(emailCodeService.verifyAndConsume("a@b.com", EmailScene.REGISTER, code)).isFalse();
    }

    @Test
    void shouldCleanupOnMailFailureAndAllowRetry() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> emailCodeService.sendCode("a@b.com", EmailScene.REGISTER))
                .isInstanceOf(AuthApiException.class)
                .hasFieldOrPropertyWithValue("code", "INTERNAL_ERROR");

        assertThat(redisTemplate.hasKey("auth:email_code:register:a@b.com")).isFalse();
        assertThat(redisTemplate.hasKey("auth:email_code:cd:register:a@b.com")).isFalse();
    }
}
