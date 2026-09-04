package com.fancy.taxiagent.rag.exception;

public class QaNotFoundException extends RuntimeException {
    public QaNotFoundException(String message) {
        super(message);
    }
}
