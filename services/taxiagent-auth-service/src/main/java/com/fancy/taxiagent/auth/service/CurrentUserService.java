package com.fancy.taxiagent.auth.service;

import com.fancy.taxiagent.auth.client.user.UserProfileClientResponse;
import com.fancy.taxiagent.auth.client.user.UserProfileRemoteService;
import com.fancy.taxiagent.auth.domain.entity.AuthAccount;
import com.fancy.taxiagent.auth.exception.AuthApiException;
import com.fancy.taxiagent.auth.service.dto.CurrentUserView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 当前用户自助服务：查询聚合视图、修改用户名/邮箱、修改密码并撤销会话。
 *
 * <p>数据归属遵循微服务边界：用户名/角色/状态在 User Service（远程 PATCH），
 * 邮箱/密码/凭证状态在 Auth 本地（taxiagent_auth.auth_account）。</p>
 *
 * <p>{@code @Lazy}：本服务依赖 {@code PasswordEncoder}，而该 Bean 仅在 Servlet Web
 * 环境下装配；与 {@link LoginService} 一致，懒加载避免非 Web 集成测试上下文启动失败。</p>
 */
@Service
@Lazy
public class CurrentUserService {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserService.class);

    private final AuthAccountService authAccountService;
    private final UserProfileRemoteService userProfileRemoteService;
    private final RefreshTokenService refreshTokenService;
    private final TokenRevocationService tokenRevocationService;
    private final PasswordEncoder passwordEncoder;

    public CurrentUserService(AuthAccountService authAccountService,
                              @Lazy UserProfileRemoteService userProfileRemoteService,
                              RefreshTokenService refreshTokenService,
                              TokenRevocationService tokenRevocationService,
                              PasswordEncoder passwordEncoder) {
        this.authAccountService = authAccountService;
        this.userProfileRemoteService = userProfileRemoteService;
        this.refreshTokenService = refreshTokenService;
        this.tokenRevocationService = tokenRevocationService;
        this.passwordEncoder = passwordEncoder;
    }

    public CurrentUserView getCurrent(Long userId) {
        AuthAccount account = requireAccount(userId);
        UserProfileClientResponse profile = userProfileRemoteService.getProfile(userId);
        return new CurrentUserView(
                String.valueOf(userId),
                profile.username(),
                account.getEmail(),
                profile.role(),
                profile.status(),
                account.getLastLoginAt(),
                account.getCreatedAt());
    }

    public CurrentUserView updateCurrent(Long userId, String newUsername, String newEmail) {
        if ((newUsername == null || newUsername.isBlank()) && (newEmail == null || newEmail.isBlank())) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "至少传一个可修改字段");
        }
        AuthAccount account = requireAccount(userId);

        UserProfileClientResponse profile = userProfileRemoteService.getProfile(userId);
        String usernameChanged = null;
        if (newUsername != null && !newUsername.isBlank()) {
            String normalized = newUsername.trim();
            if (!normalized.equals(profile.username())) {
                usernameChanged = normalized;
            }
        }
        String emailChanged = null;
        if (newEmail != null && !newEmail.isBlank()) {
            String normalized = newEmail.trim().toLowerCase(Locale.ROOT);
            if (!normalized.equals(account.getEmail())) {
                if (authAccountService.existsActiveByEmail(normalized)) {
                    throw new AuthApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "该邮箱已注册");
                }
                emailChanged = normalized;
            }
        }
        if (usernameChanged == null && emailChanged == null) {
            return getCurrent(userId); // idempotent same values
        }
        if (usernameChanged != null) {
            profile = userProfileRemoteService.updateUsername(userId, usernameChanged);
        }
        if (emailChanged != null) {
            account.setEmail(emailChanged);
            authAccountService.updateCredentialState(account);
        }
        log.info("event=auth_current_user_updated userId={}", userId);
        return getCurrent(userId);
    }

    public void changePassword(Long userId, String newPassword) {
        if (newPassword == null || newPassword.isBlank()) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "密码不能为空");
        }
        AuthAccount account = requireAccount(userId);
        if (passwordEncoder.matches(newPassword.trim(), account.getPasswordHash())) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "SAME_PASSWORD", "新密码不能与原密码一致");
        }
        account.setPasswordHash(passwordEncoder.encode(newPassword.trim()));
        account.setTokenVersion(account.getTokenVersion() + 1);
        authAccountService.updateCredentialState(account);
        tokenRevocationService.incrementTokenVersion(userId);
        refreshTokenService.revokeAll(userId);
        log.info("event=auth_password_changed userId={}", userId);
    }

    private AuthAccount requireAccount(Long userId) {
        AuthAccount account = authAccountService.findActiveByUserId(userId);
        if (account == null) {
            throw new AuthApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在");
        }
        return account;
    }
}
