package com.fancy.taxiagent.user.poi;

/**
 * POI 字段或坐标非法时抛出。
 */
public class InvalidPoiException extends RuntimeException {

    public InvalidPoiException(String message) {
        super(message);
    }
}
