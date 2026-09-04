package com.fancy.taxiagent.rag.exception;

public class InvalidRagRequestException extends RuntimeException {
    public InvalidRagRequestException(String message) {
        super(message);
    }
}
