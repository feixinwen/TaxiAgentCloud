package com.fancy.taxiagent.rag.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag")
public class RagHealthController {

    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }
}
