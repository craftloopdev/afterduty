package com.afterduty.service;

import com.afterduty.config.UsageProperties;
import com.afterduty.dto.UsageResponse;
import com.afterduty.exception.UsageLimitException;
import org.springframework.stereotype.Component;

@Component
public class UsageGuard {
    private final UsageService usage;
    private final UsageProperties props;

    public UsageGuard(UsageService usage, UsageProperties props) {
        this.usage = usage;
        this.props = props;
    }

    public void assertCapacity(Long userId) {
        if (!props.isEnabled()) return;
        UsageResponse u = usage.getCurrentUsage(userId);
        if (u.isAtLimit()) {
            throw new UsageLimitException(u.getPeriodEnd());
        }
    }
}
