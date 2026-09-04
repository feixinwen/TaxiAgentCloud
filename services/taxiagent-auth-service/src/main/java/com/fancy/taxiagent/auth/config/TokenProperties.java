package com.fancy.taxiagent.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "taxiagent.auth.token")
public class TokenProperties {

    private long refreshTtlSeconds = 604800;
    private int failedLoginThreshold = 5;
    private long lockDurationSeconds = 900;

    public long getRefreshTtlSeconds() { return refreshTtlSeconds; }
    public void setRefreshTtlSeconds(long refreshTtlSeconds) { this.refreshTtlSeconds = refreshTtlSeconds; }
    public int getFailedLoginThreshold() { return failedLoginThreshold; }
    public void setFailedLoginThreshold(int failedLoginThreshold) { this.failedLoginThreshold = failedLoginThreshold; }
    public long getLockDurationSeconds() { return lockDurationSeconds; }
    public void setLockDurationSeconds(long lockDurationSeconds) { this.lockDurationSeconds = lockDurationSeconds; }
}
