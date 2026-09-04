package com.fancy.taxiagent.user.poi;

/**
 * POI 不存在或不属于当前用户时抛出。
 */
public class PoiNotFoundException extends RuntimeException {

    public PoiNotFoundException() {
        super("兴趣点不存在");
    }
}
