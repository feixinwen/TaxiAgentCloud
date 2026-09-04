package com.fancy.taxiagent.ticket.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
public class TicketHealthController {

    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }
}
