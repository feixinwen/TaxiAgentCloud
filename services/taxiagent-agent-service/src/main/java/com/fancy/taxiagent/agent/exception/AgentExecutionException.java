package com.fancy.taxiagent.agent.exception;

/**
 * Agent 执行下游依赖调用时可稳定处理的异常。
 */
public class AgentExecutionException extends RuntimeException {

    private final String code;

    /**
     * 创建带有稳定错误码的 Agent 执行异常。
     *
     * @param code 稳定错误码
     * @param message 对调用方安全的错误信息
     * @param cause 原始异常
     */
    public AgentExecutionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * 返回稳定错误码。
     *
     * @return 稳定错误码
     */
    public String getCode() {
        return code;
    }
}
