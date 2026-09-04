package com.fancy.taxiagent.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fancy.taxiagent.auth.domain.entity.AuthAccount;
import org.apache.ibatis.annotations.Mapper;

/**
 * 认证账号表的 MyBatis-Plus 数据访问接口。
 *
 * <p>调用方只能访问 Auth Service 自己的数据源，不得通过该接口查询 User Service 数据。</p>
 */
@Mapper
public interface AuthAccountMapper extends BaseMapper<AuthAccount> {
}
