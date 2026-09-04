package com.fancy.taxiagent.rag.exception;

public class EmbeddingErrorException extends RuntimeException {
    public EmbeddingErrorException(String message) {
        super(message);
    }

    public EmbeddingErrorException(String message, Throwable cause) {
        super(message, cause);
    }
}
