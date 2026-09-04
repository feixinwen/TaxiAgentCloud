package com.fancy.taxiagent.user.domain.enums;

/**
 * 批量状态变更操作。
 */
public enum BatchAction {
    /**
     * 禁用：状态置为 0
     */
    DISABLE,

    /**
     * 激活：状态置为 1
     */
    ACTIVATE,

    /**
     * 逻辑删除：仅处理未删除行
     */
    DELETE
}
