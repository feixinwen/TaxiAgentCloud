package com.fancy.taxiagent.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fancy.taxiagent.agent.domain.entity.AgentRun;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 执行记录表的 MyBatis-Plus 数据访问接口。
 */
@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRun> {
}
