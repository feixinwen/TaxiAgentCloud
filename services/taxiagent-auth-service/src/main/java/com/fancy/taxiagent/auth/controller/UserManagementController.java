package com.fancy.taxiagent.auth.controller;

import com.fancy.taxiagent.auth.controller.dto.AdminUserCreateRequest;
import com.fancy.taxiagent.auth.controller.dto.AdminUserIdListRequest;
import com.fancy.taxiagent.auth.controller.dto.AdminUserPageRequest;
import com.fancy.taxiagent.auth.controller.dto.AdminUserUpdateRequest;
import com.fancy.taxiagent.auth.controller.dto.CurrentUserUpdateRequest;
import com.fancy.taxiagent.auth.controller.dto.PasswordChangeRequest;
import com.fancy.taxiagent.auth.exception.AuthApiException;
import com.fancy.taxiagent.auth.service.AdminUserService;
import com.fancy.taxiagent.auth.service.CurrentUserService;
import com.fancy.taxiagent.auth.service.dto.AdminUserPageView;
import com.fancy.taxiagent.auth.service.dto.CurrentUserView;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户管理接口：当前用户自助三件套 + 管理员用户管理。
 *
 * <p>当前用户三件套（/api/users/current/**）要求有效 JWT（由
 * {@code AuthSecurityConfiguration} 的 {@code anyRequest().authenticated()} 统一保护），
 * 当前用户 ID 取自 JWT subject。</p>
 *
 * <p>管理员接口（/api/users/admin/**）额外要求 ADMIN 角色，
 * 通过 {@code @PreAuthorize("hasRole('ADMIN')")} 在方法级强制。</p>
 */
@RestController
@RequestMapping("/api/users")
public class UserManagementController {

    private final CurrentUserService currentUserService;
    private final AdminUserService adminUserService;

    public UserManagementController(CurrentUserService currentUserService, AdminUserService adminUserService) {
        this.currentUserService = currentUserService;
        this.adminUserService = adminUserService;
    }

    @GetMapping("/current")
    public CurrentUserView getCurrent(Authentication authentication) {
        return currentUserService.getCurrent(currentUserId(authentication));
    }

    @PostMapping("/current/update")
    public CurrentUserView updateCurrent(@RequestBody CurrentUserUpdateRequest request, Authentication authentication) {
        return currentUserService.updateCurrent(currentUserId(authentication),
                request == null ? null : request.username(),
                request == null ? null : request.email());
    }

    @PostMapping("/current/password/reset")
    public void changePassword(@RequestBody PasswordChangeRequest request, Authentication authentication) {
        currentUserService.changePassword(currentUserId(authentication),
                request == null ? null : request.password());
    }

    @PostMapping("/admin/create")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> adminCreate(@RequestBody AdminUserCreateRequest request) {
        if (request == null) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求不能为空");
        }
        Long userId = adminUserService.create(request.username(), request.password(), request.role());
        return Map.of("userId", String.valueOf(userId));
    }

    @PostMapping("/admin/update")
    @PreAuthorize("hasRole('ADMIN')")
    public void adminUpdate(@RequestBody AdminUserUpdateRequest request) {
        if (request == null) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求不能为空");
        }
        adminUserService.update(request.userId(), request.username(), request.email(),
                request.password(), request.role());
    }

    @PostMapping("/admin/page")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminUserPageView adminPage(@RequestBody AdminUserPageRequest request) {
        return adminUserService.page(
                request == null ? null : request.username(),
                request == null ? null : request.role(),
                request == null ? null : request.deleted(),
                request == null || request.pageNum() == null ? 1 : request.pageNum(),
                request == null || request.pageSize() == null ? 10 : request.pageSize());
    }

    @PostMapping("/admin/support/page")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminUserPageView adminSupportPage(@RequestBody AdminUserPageRequest request) {
        return adminUserService.pageSupport(
                request == null ? null : request.username(),
                request == null || request.pageNum() == null ? 1 : request.pageNum(),
                request == null || request.pageSize() == null ? 10 : request.pageSize());
    }

    @PostMapping("/admin/batch/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Integer> adminBatchStatus(@RequestBody AdminUserIdListRequest request) {
        if (request == null) {
            throw new AuthApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求不能为空");
        }
        int affected = adminUserService.batchStatus(request.userIds(), request.action());
        return Map.of("affected", affected);
    }

    private Long currentUserId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new AuthApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "登录已过期，请重新登录");
        }
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException exception) {
            throw new AuthApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "登录已过期，请重新登录");
        }
    }
}
