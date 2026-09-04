package com.fancy.taxiagent.ticket.exception;

import org.springframework.security.access.AccessDeniedException;

public class TicketAccessDeniedException extends AccessDeniedException {
    public TicketAccessDeniedException(String message) {
        super(message);
    }
}
