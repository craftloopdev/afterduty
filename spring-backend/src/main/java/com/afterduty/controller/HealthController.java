package com.afterduty.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @Value("${va-claim.version:4.0.0}")
    private String version;

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
                "status", "ok",
                "service", "afterduty-api",
                "version", version
        );
    }

    @GetMapping("/")
    public Map<String, String> root() {
        return Map.of(
                "message", "After Duty API v" + version + " — Spring Boot + Gemini extraction + Claude synthesis",
                "docs", "/docs"
        );
    }
}
