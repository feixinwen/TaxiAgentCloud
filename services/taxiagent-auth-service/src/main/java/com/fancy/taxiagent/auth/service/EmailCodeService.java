package com.fancy.taxiagent.auth.service;

import com.fancy.taxiagent.auth.config.VerificationCodeProperties;
import com.fancy.taxiagent.auth.domain.enums.EmailScene;
import com.fancy.taxiagent.auth.exception.AuthApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@Service
public class EmailCodeService {

    private static final Logger log = LoggerFactory.getLogger(EmailCodeService.class);
    private static final String DEFAULT_FROM_ADDRESS = "taxiagent@test.local";
    private static final String CODE_KEY_PREFIX = "auth:email_code:";
    private static final String COOLDOWN_KEY_PREFIX = "auth:email_code:cd:";
    private static final Random RANDOM = new Random();

    private final StringRedisTemplate redisTemplate;
    private final VerificationCodeProperties properties;
    private final JavaMailSender mailSender;
    private final MailProperties mailProperties;

    public EmailCodeService(StringRedisTemplate redisTemplate,
                            VerificationCodeProperties properties,
                            JavaMailSender mailSender,
                            MailProperties mailProperties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.mailSender = mailSender;
        this.mailProperties = mailProperties;
    }

    public void sendCode(String email, EmailScene scene) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (isInCooldown(normalized, scene)) {
            throw new AuthApiException(HttpStatus.TOO_MANY_REQUESTS, "EMAIL_CODE_COOLDOWN", "验证码发送过于频繁，请稍后再试");
        }

        String code = String.format("%06d", RANDOM.nextInt(1000000));
        redisTemplate.opsForValue().set(codeKey(normalized, scene), code, properties.getTtlSeconds(), TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(cooldownKey(normalized, scene), "1", properties.getCooldownSeconds(), TimeUnit.SECONDS);

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(resolveFromAddress());
            message.setTo(normalized);
            message.setSubject("【星智出行】注册验证码");
            message.setText(String.format("您好！您正在进行注册操作，验证码为：%s。验证码有效期为 %d 分钟，请勿泄露给他人。",
                    code, properties.getTtlSeconds() / 60));
            mailSender.send(message);
        } catch (MailException exception) {
            log.error("Failed to send email to {} scene={}", normalized, scene, exception);
            redisTemplate.delete(codeKey(normalized, scene));
            redisTemplate.delete(cooldownKey(normalized, scene));
            throw new AuthApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "邮件发送失败，请稍后重试");
        }
        log.info("event=auth_email_code_sent scene={}", scene);
    }

    /**
     * 只读校验验证码，不消费（不删除）。用于注册等流程中"先校验、成功后再消费"的编排。
     */
    public boolean verify(String email, EmailScene scene, String code) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        String stored = redisTemplate.opsForValue().get(codeKey(normalized, scene));
        return stored != null && stored.equals(code);
    }

    public boolean verifyAndConsume(String email, EmailScene scene, String code) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        String key = codeKey(normalized, scene);
        String stored = redisTemplate.opsForValue().get(key);
        if (stored == null || !stored.equals(code)) {
            return false;
        }
        redisTemplate.delete(key);
        return true;
    }

    public boolean isInCooldown(String email, EmailScene scene) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey(email.trim().toLowerCase(Locale.ROOT), scene)));
    }

    /**
     * 发件人地址：优先取 spring.mail.properties.mail.smtp.from（MAIL_FROM 可覆盖），
     * 其次取 spring.mail.username，两者都为空时回退到默认地址。
     */
    private String resolveFromAddress() {
        String from = mailProperties.getProperties().get("mail.smtp.from");
        if (from == null || from.isBlank()) {
            from = mailProperties.getUsername();
        }
        return (from == null || from.isBlank()) ? DEFAULT_FROM_ADDRESS : from;
    }

    private String codeKey(String email, EmailScene scene) {
        return CODE_KEY_PREFIX + scene.name().toLowerCase(Locale.ROOT) + ":" + email;
    }

    private String cooldownKey(String email, EmailScene scene) {
        return COOLDOWN_KEY_PREFIX + scene.name().toLowerCase(Locale.ROOT) + ":" + email;
    }
}
