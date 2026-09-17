package com.afterduty.controller;

import com.afterduty.config.SecurityConfig;
import com.afterduty.dto.UsageBreakdownResponse;
import com.afterduty.dto.UsageResponse;
import com.afterduty.model.User;
import com.afterduty.service.UsageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageService usageService;

    public UsageController(UsageService usageService) {
        this.usageService = usageService;
    }

    @GetMapping
    public UsageResponse current(HttpServletRequest request) {
        User user = getUser(request);
        return usageService.getCurrentUsage(user.getId());
    }

    /**
     * "Analyze my usage" — per-feature breakdown of the caller's AI spend for
     * the current billing period. Scoped to the caller's own userId; read-only
     * over the AiCallLog ledger. Period + effective limit mirror GET /api/usage.
     */
    @GetMapping("/breakdown")
    public UsageBreakdownResponse breakdown(HttpServletRequest request) {
        User user = getUser(request);
        return usageService.getUsageBreakdown(user.getId());
    }

    private User getUser(HttpServletRequest request) {
        User user = (User) request.getAttribute(SecurityConfig.USER_ATTRIBUTE);
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        return user;
    }
}
