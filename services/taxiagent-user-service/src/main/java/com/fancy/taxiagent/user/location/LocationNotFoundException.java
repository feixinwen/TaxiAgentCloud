package com.fancy.taxiagent.user.location;

/**
 * 当前用户无位置数据时抛出。
 */
public class LocationNotFoundException extends RuntimeException {

    public LocationNotFoundException() {
        super("当前位置不存在");
    }
}
