package com.fancy.taxiagent.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fancy.taxiagent.user.command.CreateUserProfileCommand;
import com.fancy.taxiagent.user.domain.entity.UserProfile;
import com.fancy.taxiagent.user.domain.enums.BatchAction;
import com.fancy.taxiagent.user.domain.enums.UserRole;
import com.fancy.taxiagent.user.dto.UserProfilePageView;
import com.fancy.taxiagent.user.dto.UserProfileView;
import com.fancy.taxiagent.user.exception.InvalidUserProfileException;
import com.fancy.taxiagent.user.exception.UserProfileConflictException;
import com.fancy.taxiagent.user.exception.UserProfileNotFoundException;
import com.fancy.taxiagent.user.mapper.UserProfileMapper;
import com.fancy.taxiagent.user.outbox.UserEventPublisher;
import com.fancy.taxiagent.user.outbox.UserEventType;
import com.fancy.taxiagent.user.service.UserProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 用户资料应用服务默认实现。
 *
 * <p>负责输入规范化、唯一性校验、幂等创建以及实体和应用层视图之间的转换。</p>
 */
@Service
public class UserProfileServiceImpl implements UserProfileService {

    private static final int USERNAME_MAX_LENGTH = 50;
    private static final Logger log = LoggerFactory.getLogger(UserProfileServiceImpl.class);

    private final UserProfileMapper userProfileMapper;
    private final UserEventPublisher userEventPublisher;

    public UserProfileServiceImpl(UserProfileMapper userProfileMapper, UserEventPublisher userEventPublisher) {
        this.userProfileMapper = userProfileMapper;
        this.userEventPublisher = userEventPublisher;
    }

    /**
     * 校验并创建用户资料，同时处理调用方重试和并发插入产生的唯一键竞争。
     */
    @Override
    @Transactional
    public UserProfileView createProfile(CreateUserProfileCommand command) {
        NormalizedProfile normalized = validateAndNormalize(command);

        UserProfile existingById = userProfileMapper.selectById(normalized.userId());
        if (existingById != null) {
            return handleExistingProfile(existingById, normalized);
        }

        UserProfile existingByUsername = findByUsernameAndRole(normalized.username(), normalized.role());
        if (existingByUsername != null) {
            log.warn(
                    "event=user_profile_create_conflict userId={} role={} conflict=username_role",
                    normalized.userId(),
                    normalized.role()
            );
            throw new UserProfileConflictException("用户名已存在");
        }

        UserProfile profile = toEntity(normalized);
        try {
            userProfileMapper.insert(profile);
        } catch (DuplicateKeyException exception) {
            // 预检查无法消除并发窗口；唯一索引是最终防线，相同载荷的竞争请求仍按幂等成功处理。
            UserProfile concurrentlyCreated = userProfileMapper.selectById(normalized.userId());
            if (concurrentlyCreated != null && matchesCreationPayload(concurrentlyCreated, normalized)) {
                log.info("event=user_profile_create_idempotent userId={} source=concurrent", normalized.userId());
                return toView(concurrentlyCreated);
            }
            log.warn(
                    "event=user_profile_create_conflict userId={} conflict=database_unique_constraint",
                    normalized.userId()
            );
            throw new UserProfileConflictException("用户ID或用户名已存在");
        }

        UserProfile created = userProfileMapper.selectById(profile.getId());
        log.info("event=user_profile_created userId={} role={}", profile.getId(), profile.getRole());
        return toView(created);
    }

    /**
     * 查询未被逻辑删除的用户资料。
     */
    @Override
    @Transactional(readOnly = true)
    public UserProfileView getProfile(Long userId) {
        requireValidUserId(userId);
        UserProfile profile = userProfileMapper.selectById(userId);
        if (profile == null) {
            log.warn("event=user_profile_not_found userId={}", userId);
            throw new UserProfileNotFoundException(userId);
        }
        return toView(profile);
    }

    /**
     * 使用规范化后的用户名和角色解析未被逻辑删除的用户资料。
     */
    @Override
    @Transactional(readOnly = true)
    public UserProfileView resolveProfile(String username, UserRole role) {
        String normalizedUsername = normalizeUsername(username);
        if (role == null) {
            throw new InvalidUserProfileException("用户角色不能为空");
        }
        UserProfile profile = findByUsernameAndRole(normalizedUsername, role);
        if (profile == null) {
            log.warn("event=user_profile_resolve_not_found role={}", role);
            throw new UserProfileNotFoundException();
        }
        return toView(profile);
    }

    /**
     * 分页查询用户资料。
     *
     * <p>deleted=1（仅已删除）时走 {@link UserProfileMapper#selectDeletedPage} 显式 SQL：
     * @TableLogic 自动附加 is_deleted = 0，wrapper.last 的原始条件落在 ORDER BY 之后，
     * 无法表达"只查已删除"；其余情况使用带 @TableLogic 自动过滤的 selectPage。</p>
     */
    @Override
    @Transactional(readOnly = true)
    public UserProfilePageView pageUsers(String username, UserRole role, Integer deleted, int pageNum, int pageSize) {
        int boundedPageSize = Math.min(Math.max(pageSize, 1), 100);
        Page<UserProfile> page;
        if (deleted != null && deleted == 1) {
            page = userProfileMapper.selectDeletedPage(
                    Page.of(pageNum, boundedPageSize),
                    StringUtils.hasText(username) ? username.trim() : null,
                    role
            );
        } else {
            LambdaQueryWrapper<UserProfile> wrapper = new LambdaQueryWrapper<>();
            if (StringUtils.hasText(username)) {
                wrapper.like(UserProfile::getUsername, username.trim());
            }
            if (role != null) {
                wrapper.eq(UserProfile::getRole, role);
            }
            wrapper.orderByDesc(UserProfile::getUpdatedAt);
            page = userProfileMapper.selectPage(Page.of(pageNum, boundedPageSize), wrapper);
        }
        return new UserProfilePageView(page.getTotal(), page.getRecords().stream().map(this::toView).toList());
    }

    /**
     * 更新用户名；同一角色下已占用时抛冲突异常，相同值按幂等成功处理。
     */
    @Override
    @Transactional
    public UserProfileView updateUsername(Long userId, String newUsername) {
        requireValidUserId(userId);
        String normalized = normalizeUsername(newUsername);
        UserProfile existing = userProfileMapper.selectById(userId);
        if (existing == null || existing.getDeleted() == 1) {
            throw new UserProfileNotFoundException(userId);
        }
        if (existing.getUsername().equals(normalized)) {
            return toView(existing); // idempotent same value
        }
        UserProfile conflict = userProfileMapper.selectOne(new LambdaQueryWrapper<UserProfile>()
                .eq(UserProfile::getUsername, normalized)
                .eq(UserProfile::getRole, existing.getRole())
                .last("LIMIT 1"));
        if (conflict != null && !conflict.getId().equals(userId)) {
            log.warn("event=user_profile_username_conflict userId={} target={}", userId, normalized);
            throw new UserProfileConflictException("用户名已存在");
        }
        existing.setUsername(normalized);
        userProfileMapper.updateById(existing);
        log.info("event=user_profile_username_updated userId={}", userId);
        return toView(userProfileMapper.selectById(userId));
    }

    /**
     * 更新用户角色。
     */
    @Override
    @Transactional
    public UserProfileView updateRole(Long userId, UserRole role) {
        requireValidUserId(userId);
        if (role == null) {
            throw new InvalidUserProfileException("用户角色不能为空");
        }
        UserProfile existing = userProfileMapper.selectById(userId);
        if (existing == null || existing.getDeleted() == 1) {
            throw new UserProfileNotFoundException(userId);
        }
        boolean roleChanged = existing.getRole() != role;
        existing.setRole(role);
        userProfileMapper.updateById(existing);
        log.info("event=user_profile_role_updated userId={} role={}", userId, role);
        if (roleChanged) {
            // 仅在实际变更时记录事件，避免相同角色的幂等更新误触发会话撤销
            userEventPublisher.recordEvent(userId, UserEventType.USER_ROLE_CHANGED, rolePayload(role));
        }
        return toView(userProfileMapper.selectById(userId));
    }

    /**
     * 批量变更用户状态；DELETE 仅逻辑删除未删除行，DISABLE/ACTIVATE 只影响状态相反的行。
     */
    @Override
    @Transactional
    public int batchStatus(List<Long> userIds, BatchAction action) {
        List<Long> distinct = userIds == null ? List.of() : userIds.stream().distinct().toList();
        if (distinct.isEmpty()) {
            throw new InvalidUserProfileException("用户ID列表不能为空");
        }
        if (action == BatchAction.DELETE) {
            int affected = 0;
            for (Long userId : distinct) {
                UserProfile existing = userProfileMapper.selectById(userId);
                if (existing != null && existing.getDeleted() == 0) {
                    userProfileMapper.deleteById(userId);
                    affected++;
                    // 与逻辑删除同一事务写入 outbox 事件
                    userEventPublisher.recordEvent(userId, UserEventType.USER_DELETED, rolePayload(existing.getRole()));
                }
            }
            log.info("event=user_profile_batch_deleted affected={}", affected);
            return affected;
        }
        int sourceStatus = action == BatchAction.DISABLE ? 1 : 0;
        int targetStatus = action == BatchAction.DISABLE ? 0 : 1;
        // 先按与 UPDATE 相同的条件锁定受影响行，保证 outbox 事件与实际变更集合一致
        List<UserProfile> affectedProfiles = userProfileMapper.selectList(
                new LambdaQueryWrapper<UserProfile>()
                        .in(UserProfile::getId, distinct)
                        .eq(UserProfile::getStatus, sourceStatus)
                        .last("FOR UPDATE"));
        int affected = userProfileMapper.update(null, new LambdaUpdateWrapper<UserProfile>()
                .in(UserProfile::getId, distinct)
                .eq(UserProfile::getStatus, sourceStatus)
                .set(UserProfile::getStatus, targetStatus));
        UserEventType eventType = action == BatchAction.DISABLE
                ? UserEventType.USER_DISABLED
                : UserEventType.USER_ACTIVATED;
        for (UserProfile profile : affectedProfiles) {
            // 与状态变更同一事务写入 outbox 事件
            userEventPublisher.recordEvent(profile.getId(), eventType, rolePayload(profile.getRole()));
        }
        log.info("event=user_profile_batch_status_updated action={} affected={}", action, affected);
        return affected;
    }

    /**
     * 相同用户ID的重复创建只有在业务字段完全一致时才视为幂等，避免重试悄悄覆盖资料。
     */
    private UserProfileView handleExistingProfile(UserProfile existing, NormalizedProfile requested) {
        if (!matchesCreationPayload(existing, requested)) {
            log.warn("event=user_profile_create_conflict userId={} conflict=existing_payload", requested.userId());
            throw new UserProfileConflictException("用户ID已存在且资料不一致");
        }
        log.info("event=user_profile_create_idempotent userId={} source=existing", requested.userId());
        return toView(existing);
    }

    private UserProfile findByUsernameAndRole(String username, UserRole role) {
        return userProfileMapper.selectOne(
                new LambdaQueryWrapper<UserProfile>()
                        .eq(UserProfile::getUsername, username)
                        .eq(UserProfile::getRole, role)
                        .last("LIMIT 1")
        );
    }

    /**
     * 对创建输入做统一规范化，保证幂等比较和数据库唯一性判断使用相同值。
     */
    private NormalizedProfile validateAndNormalize(CreateUserProfileCommand command) {
        if (command == null) {
            throw new InvalidUserProfileException("创建用户资料命令不能为空");
        }
        requireValidUserId(command.userId());
        String username = normalizeUsername(command.username());
        if (command.role() == null) {
            throw new InvalidUserProfileException("用户角色不能为空");
        }
        return new NormalizedProfile(command.userId(), username, command.role());
    }

    private String normalizeUsername(String username) {
        String normalized = normalizeRequired(username, "用户名", USERNAME_MAX_LENGTH);
        if (normalized.contains("@")) {
            throw new InvalidUserProfileException("用户名不能包含@符号");
        }
        return normalized;
    }

    private void requireValidUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new InvalidUserProfileException("用户ID必须为正整数");
        }
    }

    private String normalizeRequired(String value, String fieldName, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new InvalidUserProfileException(fieldName + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new InvalidUserProfileException(fieldName + "长度不能超过" + maxLength + "个字符");
        }
        return normalized;
    }

    private UserProfile toEntity(NormalizedProfile normalized) {
        UserProfile profile = new UserProfile();
        profile.setId(normalized.userId());
        profile.setUsername(normalized.username());
        profile.setRole(normalized.role());
        profile.setStatus(1);
        profile.setDeleted(0);
        return profile;
    }

    private boolean matchesCreationPayload(UserProfile existing, NormalizedProfile requested) {
        return Objects.equals(existing.getUsername(), requested.username())
                && existing.getRole() == requested.role();
    }

    /**
     * outbox 事件的 payload 载荷（JSON）：{"role":"X"}。
     *
     * <p>所有事件类型都携带角色信息，保证发送给 RocketMQ 的消息体非空，
     * 消费端可据此处理 USER_ROLE_CHANGED。</p>
     */
    private String rolePayload(UserRole role) {
        return "{\"role\":\"" + role.getDatabaseValue() + "\"}";
    }

    private UserProfileView toView(UserProfile profile) {
        return new UserProfileView(
                profile.getId(),
                profile.getUsername(),
                profile.getRole(),
                profile.getStatus(),
                profile.getCreatedAt(),
                profile.getUpdatedAt()
        );
    }

    private record NormalizedProfile(
            Long userId,
            String username,
            UserRole role
    ) {
    }
}
