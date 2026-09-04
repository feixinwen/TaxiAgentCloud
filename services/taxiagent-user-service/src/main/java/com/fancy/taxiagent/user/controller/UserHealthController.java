package com.fancy.taxiagent.user.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserHealthController {

    private static final Logger log = LoggerFactory.getLogger(UserHealthController.class);


    @GetMapping("/ping")
    public PingResponse ping() {
        log.info("event=user_service_ping status=success");
        return new PingResponse("taxiagent-user-service", "UP");
    }

    public record PingResponse(String service, String status) {
    }
}
