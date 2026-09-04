package com.fancy.taxiagent.user.location;

/**
 * 用户位置视图（经纬度与地址）。
 */
public record UserLocationView(String latitude, String longitude, String address) {
}
