package com.fancy.taxiagent.auth.client.user;

import com.fancy.taxiagent.auth.exception.AuthApiException;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Auth 访问 User Service 的应用层防腐门面，统一记录远程调用结果与耗时。
 *
 * <p>后续注册和登录编排应依赖本门面，不直接依赖 Feign 接口，从而集中处理超时、错误映射和可观测性。</p>
 */
@Lazy
@Service
public class UserProfileRemoteService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileRemoteService.class);

    private final UserServiceClient userServiceClient;

    public UserProfileRemoteService(UserServiceClient userServiceClient) {
        this.userServiceClient = userServiceClient;
    }

    /**
     * 幂等创建用户资料，并记录不包含个人敏感字段的调用结果。
     *
     * @param userId   全局用户 ID
     * @param username 用户名，仅传给下游且不写入远程调用日志
     * @param role     业务角色
     * @return User Service 返回的资料快照
     */
    public UserProfileClientResponse createProfile(Long userId, String username, String role) {
        return invoke("create_profile", userId, () -> {
            try {
                return userServiceClient.createProfile(userId, new CreateUserProfileClientRequest(username, role));
            } catch (FeignException exception) {
                if (exception.status() == 409) {
                    throw new AuthApiException(HttpStatus.CONFLICT, "USERNAME_CONFLICT", "用户名已存在");
                }
                throw exception;
            }
        });
    }

    /**
     * 按全局用户 ID 查询用户资料。
     *
     * @param userId 全局用户 ID
     * @return 用户资料快照
     */
    public UserProfileClientResponse getProfile(Long userId) {
        return invoke("get_profile", userId, () -> userServiceClient.getProfile(userId));
    }

    /**
     * 按用户名和角色解析用户资料；日志只记录角色，不记录用户名。
     *
     * @param username 用户名
     * @param role     业务角色
     * @return 用户资料快照
     */
    public UserProfileClientResponse resolveProfile(String username, String role) {
        return invoke("resolve_profile", null, () -> {
            try {
                return userServiceClient.resolveProfile(username, role);
            } catch (FeignException exception) {
                if (exception.status() == 404) {
                    throw new AuthApiException(HttpStatus.NOT_FOUND, "USER_PROFILE_NOT_FOUND", "用户资料不存在");
                }
                throw exception;
            }
        });
    }

    /**
     * 分页查询用户资料快照；日志不记录用户名和角色筛选值。
     *
     * @param username 用户名模糊关键字，可为 null
     * @param role     角色，可为 null
     * @param deleted  删除状态：null 全部、0 未删除、1 已删除
     * @return 分页结果快照
     */
    public UserProfilePageClientResponse pageUsers(String username, String role, Integer deleted, int pageNum, int pageSize) {
        return invoke("page_users", null, () -> userServiceClient.pageUsers(
                username, role, deleted, pageNum, pageSize));
    }

    /**
     * 修改用户名；User Service 返回 409（用户名占用）时映射为 {@code USERNAME_CONFLICT}。
     *
     * @param userId   用户 ID
     * @param username 新用户名
     * @return 更新后的资料快照
     */
    public UserProfileClientResponse updateUsername(Long userId, String username) {
        return invoke("update_username", userId, () -> {
            try {
                return userServiceClient.updateUsername(userId, new UsernameUpdateClientRequest(username));
            } catch (FeignException exception) {
                if (exception.status() == 409) {
                    throw new AuthApiException(HttpStatus.CONFLICT, "USERNAME_CONFLICT", "用户名已存在");
                }
                throw exception;
            }
        });
    }

    /**
     * 修改业务角色。
     *
     * @param userId 用户 ID
     * @param role   新角色
     * @return 更新后的资料快照
     */
    public UserProfileClientResponse updateRole(Long userId, String role) {
        return invoke("update_role", userId, () -> userServiceClient.updateRole(
                userId, new RoleUpdateClientRequest(role)));
    }

    /**
     * 批量状态操作；撤销用户会话由领域事件驱动（Auth 消费端），本方法不做同步撤销。
     *
     * @param userIds 目标用户 ID 列表
     * @param action  DISABLE / ACTIVATE / DELETE
     * @return 受影响数量
     */
    public int batchStatus(java.util.List<Long> userIds, String action) {
        BatchStatusClientResponse response = invoke("batch_status", null,
                () -> userServiceClient.batchStatus(new BatchStatusClientRequest(userIds, action)));
        return response.affected();
    }

    private <T> T invoke(String operation, Long userId, RemoteCall<T> call) {
        long startNanos = System.nanoTime();
        try {
            T response = call.execute();
            log.info(
                    "event=auth_user_remote_call target=taxiagent-user-service operation={} traceId={} userId={} status=success durationMs={}",
                    operation,
                    MDC.get("traceId"),
                    userId,
                    elapsedMillis(startNanos)
            );
            return response;
        } catch (RuntimeException exception) {
            log.error(
                    "event=auth_user_remote_call target=taxiagent-user-service operation={} traceId={} userId={} status=failed durationMs={}",
                    operation,
                    MDC.get("traceId"),
                    userId,
                    elapsedMillis(startNanos),
                    exception
            );
            throw exception;
        }
    }

    private long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    @FunctionalInterface
    private interface RemoteCall<T> {
        T execute();
    }
}
