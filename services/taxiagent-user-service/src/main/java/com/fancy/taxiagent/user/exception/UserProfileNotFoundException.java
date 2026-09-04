package com.fancy.taxiagent.user.exception;

/**
 * 根据用户ID无法找到有效用户资料时抛出的异常。
 */
public class UserProfileNotFoundException extends RuntimeException {

    public UserProfileNotFoundException(Long userId) {
        super("用户资料不存在: userId=" + userId);
    }

    public UserProfileNotFoundException() {
        super("用户资料不存在");
    }
}
