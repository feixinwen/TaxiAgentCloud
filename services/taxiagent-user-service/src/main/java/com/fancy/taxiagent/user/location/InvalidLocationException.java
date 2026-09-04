package com.fancy.taxiagent.user.location;

/**
 * 位置坐标非法时抛出。
 */
public class InvalidLocationException extends RuntimeException {

    public InvalidLocationException(String message) {
        super(message);
    }
}
