package com.fancy.taxiagent.agent.exception;

import org.springframework.http.HttpStatus;

/**
 * 可安全映射为对外 HTTP 响应的 Agent 业务异常。
 */
public class AgentApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String runId;

    public AgentApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public AgentApiException(HttpStatus status, String code, String message, String runId) {
        super(message);
        this.status = status;
        this.code = code;
        this.runId = runId;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getRunId() {
        return runId;
    }
}
