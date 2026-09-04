package com.fancy.taxiagent.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fancy.taxiagent.auth.domain.entity.AuthAccount;
import com.fancy.taxiagent.auth.mapper.AuthAccountMapper;
import org.springframework.stereotype.Service;

@Service
public class AuthAccountService {

    private final AuthAccountMapper authAccountMapper;

    public AuthAccountService(AuthAccountMapper authAccountMapper) {
        this.authAccountMapper = authAccountMapper;
    }

    public boolean existsActiveByEmail(String email) {
        return authAccountMapper.selectCount(
                new LambdaQueryWrapper<AuthAccount>()
                        .eq(AuthAccount::getEmail, email)
                        .eq(AuthAccount::getDeleted, 0)
        ) > 0;
    }

    public void insert(AuthAccount account) {
        authAccountMapper.insert(account);
    }

    public AuthAccount findActiveByEmail(String email) {
        return authAccountMapper.selectOne(
                new LambdaQueryWrapper<AuthAccount>()
                        .eq(AuthAccount::getEmail, email)
                        .eq(AuthAccount::getDeleted, 0)
                        .last("LIMIT 1")
        );
    }

    public AuthAccount findActiveByUserId(Long userId) {
        return authAccountMapper.selectOne(
                new LambdaQueryWrapper<AuthAccount>()
                        .eq(AuthAccount::getUserId, userId)
                        .eq(AuthAccount::getDeleted, 0)
                        .last("LIMIT 1")
        );
    }

    public void updateCredentialState(AuthAccount account) {
        authAccountMapper.updateById(account);
    }
}
