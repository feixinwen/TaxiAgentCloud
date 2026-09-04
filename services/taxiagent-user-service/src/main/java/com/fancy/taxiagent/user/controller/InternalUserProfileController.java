package com.fancy.taxiagent.user.controller;

import com.fancy.taxiagent.user.command.CreateUserProfileCommand;
import com.fancy.taxiagent.user.domain.enums.UserRole;
import com.fancy.taxiagent.user.dto.BatchStatusRequest;
import com.fancy.taxiagent.user.dto.RoleUpdateRequest;
import com.fancy.taxiagent.user.dto.UsernameUpdateRequest;
import com.fancy.taxiagent.user.dto.UserProfilePageView;
import com.fancy.taxiagent.user.dto.UserProfileView;
import com.fancy.taxiagent.user.exception.InvalidUserProfileException;
import com.fancy.taxiagent.user.request.CreateUserProfileRequest;
import com.fancy.taxiagent.user.response.UserProfileResponse;
import com.fancy.taxiagent.user.service.UserProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * User Service 提供给其他可信微服务的用户资料接口。
 *
 * <p>接口要求 Auth Service 的短期服务身份 Token，并按读写 scope 授权；Gateway 不暴露该路径，
 * 生产部署还应通过集群网络策略阻止外部客户端直接访问。</p>
 */
@RestController
@RequestMapping("/internal/users")
public class InternalUserProfileController {

    private final UserProfileService userProfileService;

    public InternalUserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    /**
     * 按调用方提供的全局用户 ID 幂等创建用户资料。
     *
     * @param userId  跨服务用户唯一标识
     * @param request 创建资料请求
     * @return 创建或已存在的相同用户资料
     */
    @PutMapping("/{userId}/profile")
    public UserProfileResponse createProfile(
            @PathVariable Long userId,
            @Valid @RequestBody CreateUserProfileRequest request
    ) {
        UserRole role = parseRole(request.role());
        return UserProfileResponse.from(userProfileService.createProfile(
                new CreateUserProfileCommand(userId, request.username(), role)
        ));
    }

    /**
     * 根据全局用户 ID 查询有效用户资料。
     *
     * @param userId 跨服务用户唯一标识
     * @return 用户资料
     */
    @GetMapping("/{userId}/profile")
    public UserProfileResponse getProfile(@PathVariable Long userId) {
        return UserProfileResponse.from(userProfileService.getProfile(userId));
    }

    /**
     * 根据用户名和角色解析有效用户资料，供 Auth Service 的用户名登录流程使用。
     *
     * @param username 用户名
     * @param role     业务角色文本
     * @return 用户资料
     */
    @GetMapping("/resolve")
    public UserProfileResponse resolveProfile(
            @RequestParam String username,
            @RequestParam String role
    ) {
        return UserProfileResponse.from(userProfileService.resolveProfile(username, parseRole(role)));
    }

    /**
     * 分页查询用户资料，支持用户名模糊、角色与逻辑删除过滤。
     *
     * <p>deleted: null/0-仅未删除（默认），1-仅已删除。角色忽略大小写。</p>
     *
     * @param username 用户名模糊关键字，可选
     * @param role     业务角色文本，可选
     * @param deleted  逻辑删除过滤，可选
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数，1~100
     * @return 用户资料分页视图
     */
    @GetMapping("/page")
    public UserProfilePageView page(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Integer deleted,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return userProfileService.pageUsers(
                username,
                role == null ? null : parseRole(role),
                deleted,
                pageNum,
                pageSize
        );
    }

    /**
     * 更新用户名；同一角色下已占用返回 409。
     *
     * @param userId  用户ID
     * @param request 新用户名
     * @return 更新后的用户资料
     */
    @PatchMapping("/{userId}/username")
    public UserProfileView updateUsername(
            @PathVariable Long userId,
            @RequestBody UsernameUpdateRequest request) {
        return userProfileService.updateUsername(userId, request.username());
    }

    /**
     * 更新用户角色。
     *
     * @param userId  用户ID
     * @param request 新角色
     * @return 更新后的用户资料
     */
    @PatchMapping("/{userId}/role")
    public UserProfileView updateRole(
            @PathVariable Long userId,
            @RequestBody RoleUpdateRequest request) {
        return userProfileService.updateRole(userId, request.role());
    }

    /**
     * 批量状态变更：DISABLE/ACTIVATE 改状态，DELETE 逻辑删除。
     *
     * @param request 用户ID列表与操作
     * @return 受影响行数
     */
    @PostMapping("/batch/status")
    public Map<String, Integer> batchStatus(@RequestBody BatchStatusRequest request) {
        return Map.of("affected", userProfileService.batchStatus(request.userIds(), request.action()));
    }

    private UserRole parseRole(String role) {
        try {
            return UserRole.fromString(role);
        } catch (IllegalArgumentException exception) {
            throw new InvalidUserProfileException(exception.getMessage());
        }
    }
}
