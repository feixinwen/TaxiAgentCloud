package com.fancy.taxiagent.user.service;

import com.fancy.taxiagent.user.command.CreateUserProfileCommand;
import com.fancy.taxiagent.user.domain.enums.BatchAction;
import com.fancy.taxiagent.user.domain.enums.UserRole;
import com.fancy.taxiagent.user.dto.UserProfilePageView;
import com.fancy.taxiagent.user.dto.UserProfileView;

import java.util.List;

/**
 * 用户资料应用服务。
 *
 * <p>该服务只处理 User Service 拥有的资料数据，不处理邮箱、密码、Token 等认证凭据。</p>
 */
public interface UserProfileService {

    /**
     * 创建用户资料。
     *
     * <p>调用方必须提供已经生成的跨服务用户ID。相同ID和相同资料的重复请求按幂等成功处理，
     * 相同ID但资料不同或用户名已被占用时抛出冲突异常。</p>
     *
     * @param command 创建用户资料命令
     * @return 已持久化的用户资料视图
     */
    UserProfileView createProfile(CreateUserProfileCommand command);

    /**
     * 根据跨服务用户ID查询有效用户资料。
     *
     * @param userId 跨服务用户唯一标识
     * @return 用户资料视图
     */
    UserProfileView getProfile(Long userId);

    /**
     * 根据用户名和业务角色解析有效用户资料。
     *
     * <p>该能力用于 Auth Service 的用户名登录流程；用户名在不同角色之间可以重复，
     * 因此调用方必须同时提供角色。</p>
     *
     * @param username 用户名
     * @param role     业务角色
     * @return 用户资料视图
     */
    UserProfileView resolveProfile(String username, UserRole role);

    /**
     * 分页查询用户资料。
     *
     * <p>deleted 为空时只返回未删除用户（@TableLogic 默认行为）；deleted=0 等价于默认；
     * deleted=1 时返回已逻辑删除的用户。结果按 updated_at 倒序排列。</p>
     *
     * @param username 用户名模糊关键字，可选
     * @param role     业务角色，可选
     * @param deleted  逻辑删除过滤：null-默认（未删除），0-未删除，1-已删除
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数，1~100
     * @return 用户资料分页视图
     */
    UserProfilePageView pageUsers(String username, UserRole role, Integer deleted, int pageNum, int pageSize);

    /**
     * 更新用户名，同一角色下已占用时抛出冲突异常；相同值按幂等成功处理。
     *
     * @param userId     用户ID
     * @param newUsername 新用户名
     * @return 更新后的用户资料视图
     */
    UserProfileView updateUsername(Long userId, String newUsername);

    /**
     * 更新用户角色。
     *
     * @param userId 用户ID
     * @param role   新角色
     * @return 更新后的用户资料视图
     */
    UserProfileView updateRole(Long userId, UserRole role);

    /**
     * 批量变更用户状态。
     *
     * <p>DISABLE 将启用用户置为禁用；ACTIVATE 将禁用用户置为启用；DELETE 仅逻辑删除未删除用户。</p>
     *
     * @param userIds 用户ID列表
     * @param action  批量操作
     * @return 受影响的行数
     */
    int batchStatus(List<Long> userIds, BatchAction action);
}
