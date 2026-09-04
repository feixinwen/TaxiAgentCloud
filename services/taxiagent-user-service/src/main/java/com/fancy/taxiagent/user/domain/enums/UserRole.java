package com.fancy.taxiagent.user.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

import java.util.Locale;

/**
 * 用户在 TaxiAgent 业务系统中的角色。
 */
public enum UserRole {
    USER("USER"),
    ADMIN("ADMIN"),
    SUPPORT("SUPPORT"),
    DRIVER("DRIVER");

    @EnumValue
    private final String databaseValue;

    UserRole(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    /**
     * 以忽略大小写的方式解析业务角色。
     *
     * @param value 角色文本
     * @return 对应业务角色
     * @throws IllegalArgumentException 角色为空或不受支持
     */
    public static UserRole fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("用户角色不能为空");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("用户角色不合法", exception);
        }
    }

    public String getDatabaseValue() {
        return databaseValue;
    }
}
