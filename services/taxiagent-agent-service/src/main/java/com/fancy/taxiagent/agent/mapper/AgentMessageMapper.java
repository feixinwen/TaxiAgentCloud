package com.fancy.taxiagent.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fancy.taxiagent.agent.domain.entity.AgentMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 消息表的 MyBatis-Plus 数据访问接口。
 */
@Mapper
public interface AgentMessageMapper extends BaseMapper<AgentMessage> {
}
