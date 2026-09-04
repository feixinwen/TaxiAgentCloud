package com.fancy.taxiagent.user.exception;

/**
 * 用户资料命令不满足字段或业务约束时抛出的异常。
 */
public class InvalidUserProfileException extends RuntimeException {

    public InvalidUserProfileException(String message) {
        super(message);
    }
}
