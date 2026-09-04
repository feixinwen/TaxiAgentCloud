package com.fancy.taxiagent.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fancy.taxiagent.user.domain.entity.UserPoi;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户常用地点表的 MyBatis-Plus 数据访问接口。
 */
@Mapper
public interface UserPoiMapper extends BaseMapper<UserPoi> {
}
