package com.fancy.taxiagent.ticket.exception;

public class TicketStateConflictException extends RuntimeException {
    public TicketStateConflictException(String message) {
        super(message);
    }
}
