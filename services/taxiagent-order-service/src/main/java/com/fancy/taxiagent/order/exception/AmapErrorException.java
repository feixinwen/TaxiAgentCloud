package com.fancy.taxiagent.order.exception;

public class AmapErrorException extends RuntimeException {
    public AmapErrorException(String message) {
        super(message);
    }

    public AmapErrorException(String message, Throwable cause) {
        super(message, cause);
    }
}
