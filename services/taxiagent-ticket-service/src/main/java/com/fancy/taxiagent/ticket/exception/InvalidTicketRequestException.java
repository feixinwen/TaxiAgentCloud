package com.fancy.taxiagent.ticket.exception;

public class InvalidTicketRequestException extends RuntimeException {
    public InvalidTicketRequestException(String message) {
        super(message);
    }
}
