package com.fancy.taxiagent.auth.service;

import com.fancy.taxiagent.auth.client.user.UserProfileRemoteService;
import com.fancy.taxiagent.auth.domain.entity.AuthAccount;
import com.fancy.taxiagent.auth.domain.enums.CredentialStatus;
import com.fancy.taxiagent.auth.domain.enums.EmailScene;
import com.fancy.taxiagent.auth.exception.AuthApiException;
import com.fancy.taxiagent.auth.id.IdGenerator;
import com.fancy.taxiagent.auth.service.dto.LoginResult;
import com.fancy.taxiagent.auth.service.dto.RegisterCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 公开注册编排：校验 → 邮箱去重 → 只读校验验证码 → 生成 ID → 远程建档 → 本地落库 →
 * 消费验证码 → 签发会话（Access Token + Refresh Token）。
 *
 * <p>顺序约定（spec §4）：任何校验失败都不得留下本地凭证或消费验证码；
 * 远程建档失败不写入本地，验证码保留供重试；本地唯一键冲突按并发注册处理。</p>
 *
 * <p>本服务延迟实例化：它依赖 JWT 密钥等重型基础设施，懒加载使不涉及签发的
 * 数据库/Redis 测试无需配置密钥（与 JwtAccessTokenService 等保持一致）。</p>
 */
@Lazy
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);
    private static final int USERNAME_MAX_LENGTH = 50;

    private final EmailCodeService emailCodeService;
    private final AuthAccountService authAccountService;
    private final UserProfileRemoteService userProfileRemoteService;
    private final IdGenerator idGenerator;
    private final RefreshTokenService refreshTokenService;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(EmailCodeService emailCodeService,
                               AuthAccountService authAccountService,
                               UserProfileRemoteService userProfileRemoteService,
                               IdGenerator idGenerator,
                               RefreshTokenService refreshTokenService,
                               PasswordEncoder passwordEncoder) {
        this.emailCodeService = emailCodeService;
        this.authAccountService = authAccountService;
        this.userProfileRemoteService = userProfileRemoteService;
        this.idGenerator = idGenerator;
        this.refreshTokenService = refreshTokenService;
        this.passwordEncoder = passwordEncoder;
    }

    public LoginResult register(RegisterCommand command) {
        String email = validateEmail(command.email());
        if (command.password() == null || command.password().isBlank()) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "密码不能为空");
        }
        String password = command.password().trim();
        String role = resolveRole(command.role());
        String username = normalizeUsername(command.username()); // null → default computed after userId

        if (authAccountService.existsActiveByEmail(email)) {
            throw new AuthApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "该邮箱已注册");
        }
        if (!emailCodeService.verify(email, EmailScene.REGISTER, command.code())) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL_CODE", "验证码无效或已过期");
        }

        Long userId = idGenerator.nextId();
        String effectiveUsername = username != null ? username : "user_" + userId;
        userProfileRemoteService.createProfile(userId, effectiveUsername, role);

        AuthAccount account = new AuthAccount();
        account.setUserId(userId);
        account.setEmail(email);
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setCredentialStatus(CredentialStatus.ACTIVE);
        account.setTokenVersion(0L);
        account.setFailedLoginCount(0);
        account.setDeleted(0);
        try {
            authAccountService.insert(account);
        } catch (DuplicateKeyException exception) {
            throw new AuthApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "该邮箱已注册");
        }

        emailCodeService.verifyAndConsume(email, EmailScene.REGISTER, command.code());

        log.info("event=auth_user_registered userId={} role={}", userId, role);
        return refreshTokenService.issuePair(userId, role, account.getTokenVersion(), effectiveUsername);
    }

    private String validateEmail(String email) {
        String value = requireText(email, "邮箱不能为空");
        if (!value.contains("@") || !value.contains(".")) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL", "邮箱格式不正确");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL", message);
        }
        return value;
    }

    private String resolveRole(String rawRole) {
        if (rawRole == null || rawRole.isBlank()) {
            return "USER";
        }
        String role = rawRole.trim().toUpperCase(Locale.ROOT);
        if (!role.equals("USER") && !role.equals("DRIVER")) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_ROLE", "不支持的角色类型");
        }
        return role;
    }

    private String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            return null; // caller computes user_{userId}
        }
        String normalized = username.trim();
        if (normalized.length() > USERNAME_MAX_LENGTH) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "用户名长度不能超过50个字符");
        }
        if (normalized.contains("@")) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "用户名不能包含@符号");
        }
        return normalized;
    }
}
