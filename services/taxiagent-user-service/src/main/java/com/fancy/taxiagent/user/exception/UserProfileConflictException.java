package com.fancy.taxiagent.user.exception;

/**
 * 用户ID或用户名与已有用户资料发生冲突时抛出的异常。
 */
public class UserProfileConflictException extends RuntimeException {

    public UserProfileConflictException(String message) {
        super(message);
    }
}
