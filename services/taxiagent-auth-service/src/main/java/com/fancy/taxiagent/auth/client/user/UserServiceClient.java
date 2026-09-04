package com.fancy.taxiagent.auth.client.user;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Auth Service 访问 User Service 内部用户资料契约的 Feign 客户端。
 *
 * <p>客户端通过 Nacos 服务名寻址，并由专用拦截器携带短期服务身份 Token 和 traceId。</p>
 */
@FeignClient(
        name = "taxiagent-user-service",
        path = "/internal/users",
        configuration = UserServiceFeignConfiguration.class
)
@Lazy
public interface UserServiceClient {

    /**
     * 幂等创建与认证账号共享同一 userId 的用户资料。
     *
     * @param userId  全局用户 ID
     * @param request 用户名与角色
     * @return 创建或已存在的用户资料
     */
    @PutMapping("/{userId}/profile")
    UserProfileClientResponse createProfile(
            @PathVariable("userId") Long userId,
            @RequestBody CreateUserProfileClientRequest request
    );

    /**
     * 按全局用户 ID 查询有效用户资料。
     *
     * @param userId 全局用户 ID
     * @return 用户资料快照
     */
    @GetMapping("/{userId}/profile")
    UserProfileClientResponse getProfile(@PathVariable("userId") Long userId);

    /**
     * 按用户名与业务角色解析用户资料。
     *
     * @param username 用户名
     * @param role     业务角色
     * @return 用户资料快照
     */
    @GetMapping("/resolve")
    UserProfileClientResponse resolveProfile(
            @RequestParam("username") String username,
            @RequestParam("role") String role
    );

    /**
     * 分页查询用户资料（支持用户名模糊、角色、删除状态筛选）。
     */
    @GetMapping("/page")
    UserProfilePageClientResponse pageUsers(
            @RequestParam("username") String username,
            @RequestParam("role") String role,
            @RequestParam("deleted") Integer deleted,
            @RequestParam("pageNum") int pageNum,
            @RequestParam("pageSize") int pageSize
    );

    /**
     * 修改用户名（幂等；占用返回 409）。
     */
    @PatchMapping("/{userId}/username")
    UserProfileClientResponse updateUsername(
            @PathVariable("userId") Long userId,
            @RequestBody UsernameUpdateClientRequest request
    );

    /**
     * 修改业务角色。
     */
    @PatchMapping("/{userId}/role")
    UserProfileClientResponse updateRole(
            @PathVariable("userId") Long userId,
            @RequestBody RoleUpdateClientRequest request
    );

    /**
     * 批量状态操作（DISABLE / ACTIVATE / DELETE）。
     */
    @PostMapping("/batch/status")
    BatchStatusClientResponse batchStatus(@RequestBody BatchStatusClientRequest request);
}
