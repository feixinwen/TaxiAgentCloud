package com.fancy.taxiagent.auth.service;

import com.fancy.taxiagent.auth.client.user.UserProfileClientResponse;
import com.fancy.taxiagent.auth.client.user.UserProfileRemoteService;
import com.fancy.taxiagent.auth.config.TokenProperties;
import com.fancy.taxiagent.auth.domain.entity.AuthAccount;
import com.fancy.taxiagent.auth.domain.enums.EmailScene;
import com.fancy.taxiagent.auth.exception.AuthApiException;
import com.fancy.taxiagent.auth.service.dto.LoginResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;

@Service
@Lazy
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final AuthAccountService authAccountService;
    private final RefreshTokenService refreshTokenService;
    private final UserProfileRemoteService userProfileRemoteService;
    private final PasswordEncoder passwordEncoder;
    private final TokenProperties tokenProperties;
    private final EmailCodeService emailCodeService;
    private final TokenRevocationService tokenRevocationService;

    public LoginService(AuthAccountService authAccountService,
                        @Lazy RefreshTokenService refreshTokenService,
                        @Lazy UserProfileRemoteService userProfileRemoteService,
                        PasswordEncoder passwordEncoder,
                        TokenProperties tokenProperties,
                        EmailCodeService emailCodeService,
                        TokenRevocationService tokenRevocationService) {
        this.authAccountService = authAccountService;
        this.refreshTokenService = refreshTokenService;
        this.userProfileRemoteService = userProfileRemoteService;
        this.passwordEncoder = passwordEncoder;
        this.tokenProperties = tokenProperties;
        this.emailCodeService = emailCodeService;
        this.tokenRevocationService = tokenRevocationService;
    }

    public LoginResult loginByPassword(String login, String password) {
        LoginPrincipal principal = parseLoginPrincipal(login);
        CredentialLookup lookup = findCredential(principal);
        AuthAccount account = lookup.account();
        if (account == null) {
            throw invalidCredentials();
        }
        if (isLocked(account)) {
            throw new AuthApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_LOCKED", "账号已锁定，请稍后再试");
        }
        if (!passwordEncoder.matches(password, account.getPasswordHash())) {
            handleFailedAttempt(account);
        }
        account.setFailedLoginCount(0);
        account.setLockedUntil(null);
        account.setLastLoginAt(LocalDateTime.now());
        authAccountService.updateCredentialState(account);

        // 用户名登录在定位凭证时已解析过资料，直接复用；邮箱登录此时才查询
        UserProfileClientResponse profile = lookup.profile() != null
                ? lookup.profile()
                : userProfileRemoteService.getProfile(account.getUserId());
        if (profile.status() == null || profile.status() != 1) {
            throw new AuthApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "账号已被禁用");
        }
        log.info("event=auth_user_logged_in userId={} method=password", account.getUserId());
        return refreshTokenService.issuePair(account.getUserId(), profile.role(), account.getTokenVersion(), profile.username());
    }

    public LoginResult loginByEmailCode(String email, String code) {
        if (!emailCodeService.verifyAndConsume(email, EmailScene.LOGIN, code)) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL_CODE", "验证码无效或已过期");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        AuthAccount account = authAccountService.findActiveByEmail(normalized);
        if (account == null) {
            throw new AuthApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_NOT_FOUND", "账号不存在");
        }
        UserProfileClientResponse profile = userProfileRemoteService.getProfile(account.getUserId());
        if (!"USER".equals(profile.role())) {
            throw new AuthApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "该账号不支持验证码登录");
        }
        if (profile.status() == null || profile.status() != 1) {
            throw new AuthApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "账号已被禁用");
        }
        log.info("event=auth_user_logged_in userId={} method=email_code", account.getUserId());
        return refreshTokenService.issuePair(account.getUserId(), profile.role(), account.getTokenVersion(), profile.username());
    }

    public LoginResult refresh(String refreshToken) {
        Long userId = refreshTokenService.resolveUserId(refreshToken);
        AuthAccount account = userId == null ? null : authAccountService.findActiveByUserId(userId);
        if (account == null || isLocked(account)) {
            if (userId != null) {
                refreshTokenService.revokeAll(userId);
            }
            throw new AuthApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "登录已过期，请重新登录");
        }
        UserProfileClientResponse profile = userProfileRemoteService.getProfile(userId);
        return refreshTokenService.rotate(refreshToken, profile.role(), profile.username());
    }

    public void logout(Long userId, String jti, long jtiTtlSeconds) {
        refreshTokenService.revokeAll(userId);
        tokenRevocationService.blacklistJti(jti, jtiTtlSeconds);
        log.info("event=auth_user_logged_out userId={}", userId);
    }

    public boolean isUsernameAvailable(String username) {
        try {
            userProfileRemoteService.resolveProfile(username, "USER");
            return false;
        } catch (AuthApiException exception) {
            if ("USER_PROFILE_NOT_FOUND".equals(exception.getCode())) {
                return true;
            }
            throw exception;
        }
    }

    private CredentialLookup findCredential(LoginPrincipal principal) {
        if (principal.email() != null) {
            return new CredentialLookup(authAccountService.findActiveByEmail(principal.email()), null);
        }
        try {
            UserProfileClientResponse profile = userProfileRemoteService
                    .resolveProfile(principal.username(), principal.role());
            return new CredentialLookup(authAccountService.findActiveByUserId(profile.userId()), profile);
        } catch (AuthApiException exception) {
            // 仅用户资料不存在视为凭证不存在（防枚举统一错误）；其他错误（网络等）继续抛
            if ("USER_PROFILE_NOT_FOUND".equals(exception.getCode())) {
                return new CredentialLookup(null, null);
            }
            throw exception;
        }
    }

    private void handleFailedAttempt(AuthAccount account) {
        int failed = account.getFailedLoginCount() == null ? 0 : account.getFailedLoginCount();
        failed++;
        if (failed >= tokenProperties.getFailedLoginThreshold()) {
            account.setFailedLoginCount(0);
            account.setLockedUntil(LocalDateTime.now().plusSeconds(tokenProperties.getLockDurationSeconds()));
            authAccountService.updateCredentialState(account);
            log.warn("event=auth_credential_locked userId={}", account.getUserId());
            throw new AuthApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_LOCKED", "账号已锁定，请稍后再试");
        }
        account.setFailedLoginCount(failed);
        authAccountService.updateCredentialState(account);
        throw invalidCredentials();
    }

    private boolean isLocked(AuthAccount account) {
        return account.getLockedUntil() != null && account.getLockedUntil().isAfter(LocalDateTime.now());
    }

    private AuthApiException invalidCredentials() {
        return new AuthApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
    }

    private LoginPrincipal parseLoginPrincipal(String login) {
        if (login == null || login.isBlank()) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "登录标识不能为空");
        }
        if (login.contains("#")) {
            String[] parts = login.split("#", 2);
            String username = parts[0].trim();
            String roleStr = parts[1].trim();
            if (username.isEmpty() || roleStr.isEmpty()) {
                throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "登录格式错误");
            }
            if (username.contains("@")) {
                throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "后台登录请使用 username#role 格式，不支持邮箱");
            }
            return new LoginPrincipal(null, username, roleStr.toUpperCase(Locale.ROOT));
        }
        if (login.contains("@")) {
            return new LoginPrincipal(login.trim().toLowerCase(Locale.ROOT), null, "USER");
        }
        return new LoginPrincipal(null, login.trim(), "USER");
    }

    private record LoginPrincipal(String email, String username, String role) {}

    private record CredentialLookup(AuthAccount account, UserProfileClientResponse profile) {}
}
