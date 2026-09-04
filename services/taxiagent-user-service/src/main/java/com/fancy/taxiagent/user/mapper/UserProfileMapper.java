package com.fancy.taxiagent.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fancy.taxiagent.user.domain.entity.UserProfile;
import com.fancy.taxiagent.user.domain.enums.UserRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户资料表的 MyBatis-Plus 数据访问接口。
 */
@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfile> {

    /**
     * 分页查询已逻辑删除的用户资料。
     *
     * <p>@TableLogic 会在自动注入的 SQL 上附加 is_deleted = 0，wrapper.last 追加的原始条件又位于
     * ORDER BY 之后（MyBatis-Plus 3.5.9 的 AbstractWrapper#getSqlSegment 为 expression + lastSql），
     * 无法表达"只查已删除"，因此已删除过滤使用显式 SQL。</p>
     *
     * @param page     分页参数
     * @param username 用户名模糊关键字，可选
     * @param role     业务角色，可选
     * @return 已删除用户分页结果
     */
    @Select("""
            <script>
            SELECT id, username, role, status, is_deleted, created_at, updated_at
            FROM user_profile
            WHERE is_deleted = 1
            <if test="username != null and username != ''">
                AND username LIKE CONCAT('%', #{username}, '%')
            </if>
            <if test="role != null">
                AND role = #{role}
            </if>
            ORDER BY updated_at DESC
            </script>
            """)
    Page<UserProfile> selectDeletedPage(
            Page<UserProfile> page,
            @Param("username") String username,
            @Param("role") UserRole role
    );
}
