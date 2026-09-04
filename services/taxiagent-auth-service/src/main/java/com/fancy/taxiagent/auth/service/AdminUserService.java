package com.fancy.taxiagent.auth.service;

import com.fancy.taxiagent.auth.client.user.UserProfileClientResponse;
import com.fancy.taxiagent.auth.client.user.UserProfilePageClientResponse;
import com.fancy.taxiagent.auth.client.user.UserProfileRemoteService;
import com.fancy.taxiagent.auth.domain.entity.AuthAccount;
import com.fancy.taxiagent.auth.domain.enums.CredentialStatus;
import com.fancy.taxiagent.auth.exception.AuthApiException;
import com.fancy.taxiagent.auth.id.IdGenerator;
import com.fancy.taxiagent.auth.mapper.AuthAccountMapper;
import com.fancy.taxiagent.auth.service.dto.AdminUserPageView;
import com.fancy.taxiagent.auth.service.dto.AdminUserPageView.AdminUserRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理员用户管理编排：创建 / 修改 / 分页 / 批量状态。
 *
 * <p>数据归属遵循微服务边界：用户名/角色/状态在 User Service（远程调用），
 * 邮箱/密码/凭证状态在 Auth 本地（taxiagent_auth.auth_account）。管理员创建的
 * 账号无邮箱（V3 起 {@code auth_account.email} 可空），通过用户名登录。</p>
 *
 * <p>密码变化在 Auth 本地同步撤销全部会话；角色/状态变化由领域事件驱动撤销
 * （E-B 阶段接入），本服务不做同步撤销。</p>
 */
@Service
public class AdminUserService {

    private static final int USERNAME_MAX_LENGTH = 50;
    private static final Set<String> ALLOWED_ROLES = Set.of("USER", "DRIVER", "SUPPORT", "ADMIN");
    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    private final IdGenerator idGenerator;
    private final AuthAccountService authAccountService;
    private final AuthAccountMapper authAccountMapper;
    private final UserProfileRemoteService userProfileRemoteService;
    private final RefreshTokenService refreshTokenService;
    private final TokenRevocationService tokenRevocationService;
    private final PasswordEncoder passwordEncoder;

    public AdminUserService(IdGenerator idGenerator,
                            AuthAccountService authAccountService,
                            AuthAccountMapper authAccountMapper,
                            @Lazy UserProfileRemoteService userProfileRemoteService,
                            RefreshTokenService refreshTokenService,
                            TokenRevocationService tokenRevocationService,
                            PasswordEncoder passwordEncoder) {
        this.idGenerator = idGenerator;
        this.authAccountService = authAccountService;
        this.authAccountMapper = authAccountMapper;
        this.userProfileRemoteService = userProfileRemoteService;
        this.refreshTokenService = refreshTokenService;
        this.tokenRevocationService = tokenRevocationService;
        this.passwordEncoder = passwordEncoder;
    }

    public Long create(String username, String password, String role) {
        String normalizedUsername = requireUsername(username);
        if (password == null || password.isBlank()) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "密码不能为空");
        }
        String normalizedRole = requireRole(role);

        Long userId = idGenerator.nextId();
        userProfileRemoteService.createProfile(userId, normalizedUsername, normalizedRole);

        AuthAccount account = new AuthAccount();
        account.setUserId(userId);
        account.setEmail(null);
        account.setPasswordHash(passwordEncoder.encode(password.trim()));
        account.setCredentialStatus(CredentialStatus.ACTIVE);
        account.setTokenVersion(0L);
        account.setFailedLoginCount(0);
        account.setDeleted(0);
        try {
            authAccountService.insert(account);
        } catch (DuplicateKeyException exception) {
            throw new AuthApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "账号已存在");
        }
        log.info("event=auth_admin_account_created userId={} role={}", userId, normalizedRole);
        return userId;
    }

    public void update(Long userId, String username, String email, String password, String role) {
        if (userId == null || userId <= 0) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "用户ID不合法");
        }
        boolean anyField = (username != null && !username.isBlank())
                || (email != null && !email.isBlank())
                || (password != null && !password.isBlank())
                || (role != null && !role.isBlank());
        if (!anyField) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "至少传一个可修改字段");
        }
        AuthAccount account = authAccountService.findActiveByUserId(userId);
        if (account == null) {
            throw new AuthApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在");
        }

        if (username != null && !username.isBlank()) {
            userProfileRemoteService.updateUsername(userId, requireUsername(username));
        }
        if (role != null && !role.isBlank()) {
            userProfileRemoteService.updateRole(userId, requireRole(role)); // 撤销走事件（E-B）
        }
        if (email != null && !email.isBlank()) {
            String normalized = email.trim().toLowerCase(Locale.ROOT);
            AuthAccount other = authAccountService.findActiveByEmail(normalized);
            if (other != null && !other.getUserId().equals(userId)) {
                throw new AuthApiException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "该邮箱已注册");
            }
            account.setEmail(normalized);
        }
        if (password != null && !password.isBlank()) {
            account.setPasswordHash(passwordEncoder.encode(password.trim()));
            account.setTokenVersion(account.getTokenVersion() + 1);
            tokenRevocationService.incrementTokenVersion(userId);
            refreshTokenService.revokeAll(userId);
        }
        authAccountService.updateCredentialState(account);
        log.info("event=auth_admin_account_updated userId={}", userId);
    }

    public AdminUserPageView page(String username, String role, Integer deleted, int pageNum, int pageSize) {
        UserProfilePageClientResponse page = userProfileRemoteService
                .pageUsers(username, role, deleted, pageNum, pageSize);
        return enrichWithEmails(page);
    }

    public AdminUserPageView pageSupport(String username, int pageNum, int pageSize) {
        UserProfilePageClientResponse page = userProfileRemoteService
                .pageUsers(username, "SUPPORT", 0, pageNum, pageSize);
        return enrichWithEmails(page);
    }

    public int batchStatus(List<Long> userIds, String action) {
        if (userIds == null || userIds.isEmpty()) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "用户ID列表不能为空");
        }
        if (!Set.of("DISABLE", "ACTIVATE", "DELETE").contains(action)) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "不支持的操作");
        }
        return userProfileRemoteService.batchStatus(userIds, action); // 撤销走事件（E-B）
    }

    private AdminUserPageView enrichWithEmails(UserProfilePageClientResponse page) {
        List<Long> userIds = page.records().stream().map(UserProfileClientResponse::userId).toList();
        Map<Long, String> emailByUserId = userIds.isEmpty() ? Map.of()
                : authAccountMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(AuthAccount::getUserId, AuthAccount::getEmail, (a, b) -> a));
        List<AdminUserRow> rows = page.records().stream()
                .map(p -> new AdminUserRow(
                        String.valueOf(p.userId()),
                        p.username(),
                        emailByUserId.get(p.userId()),
                        p.role(),
                        p.status(),
                        p.createdAt()))
                .toList();
        return new AdminUserPageView(page.total(), rows);
    }

    private String requireUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "用户名不能为空");
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

    private String requireRole(String role) {
        if (role == null || role.isBlank()) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "角色不能为空");
        }
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_ROLES.contains(normalized)) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "不支持的角色的类型");
        }
        return normalized;
    }
}
