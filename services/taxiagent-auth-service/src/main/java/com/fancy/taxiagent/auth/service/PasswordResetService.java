package com.fancy.taxiagent.auth.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fancy.taxiagent.auth.domain.entity.AuthAccount;
import com.fancy.taxiagent.auth.domain.enums.EmailScene;
import com.fancy.taxiagent.auth.exception.AuthApiException;
import com.fancy.taxiagent.auth.mapper.AuthAccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 忘记密码：验证码校验 → 更新密码哈希 → token_version 自增（DB + Redis）→ 撤销全部会话。
 *
 * <p>{@code @Lazy}：构造链依赖 RefreshTokenService → JwtAccessTokenService → JWT 密钥加载，
 * 与 LoginService / RefreshTokenService / UserProfileRemoteService 一致，避免在
 * 不涉及 Token 签发的全上下文测试中强制加载 JWT 密钥。</p>
 */
@Service
@Lazy
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final EmailCodeService emailCodeService;
    private final AuthAccountService authAccountService;
    private final AuthAccountMapper authAccountMapper;
    private final RefreshTokenService refreshTokenService;
    private final TokenRevocationService tokenRevocationService;
    private final PasswordEncoder passwordEncoder;

    public PasswordResetService(EmailCodeService emailCodeService,
                                AuthAccountService authAccountService,
                                AuthAccountMapper authAccountMapper,
                                RefreshTokenService refreshTokenService,
                                TokenRevocationService tokenRevocationService,
                                PasswordEncoder passwordEncoder) {
        this.emailCodeService = emailCodeService;
        this.authAccountService = authAccountService;
        this.authAccountMapper = authAccountMapper;
        this.refreshTokenService = refreshTokenService;
        this.tokenRevocationService = tokenRevocationService;
        this.passwordEncoder = passwordEncoder;
    }

    public void reset(String email, String code, String newPassword) {
        if (newPassword == null || newPassword.isBlank()) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "新密码不能为空");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (!emailCodeService.verifyAndConsume(normalized, EmailScene.RESET_PASSWORD, code)) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL_CODE", "验证码无效或已过期");
        }
        AuthAccount account = authAccountService.findActiveByEmail(normalized);
        if (account == null) {
            throw new AuthApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "账号不存在");
        }
        account.setPasswordHash(passwordEncoder.encode(newPassword.trim()));
        account.setTokenVersion(account.getTokenVersion() + 1);
        // 重置成功后清除锁定状态：被锁定的用户正是走"忘记密码"流程的主要场景。
        // MyBatis-Plus 默认更新策略跳过 null 字段，lockedUntil 置空需用 update wrapper 显式 set(null)
        account.setLockedUntil(null);
        account.setFailedLoginCount(0);
        authAccountService.updateCredentialState(account);
        authAccountMapper.update(null, new LambdaUpdateWrapper<AuthAccount>()
                .eq(AuthAccount::getUserId, account.getUserId())
                .set(AuthAccount::getLockedUntil, null)
                .set(AuthAccount::getFailedLoginCount, 0));
        tokenRevocationService.incrementTokenVersion(account.getUserId());
        refreshTokenService.revokeAll(account.getUserId());
        log.info("event=auth_password_reset userId={}", account.getUserId());
    }
}
