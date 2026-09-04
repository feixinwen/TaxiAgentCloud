package com.fancy.taxiagent.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fancy.taxiagent.agent.domain.entity.AgentConversation;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 对话表的 MyBatis-Plus 数据访问接口。
 */
@Mapper
public interface AgentConversationMapper extends BaseMapper<AgentConversation> {
}
