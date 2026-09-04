package com.fancy.taxiagent.user.outbox;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户领域事件待发布表的 MyBatis-Plus 数据访问接口。
 */
@Mapper
public interface UserEventOutboxMapper extends BaseMapper<UserEventOutbox> {
}
