package com.afterduty.exception;

import java.time.Instant;

public class UsageLimitException extends RuntimeException {
    private final Instant periodEnd;
    public UsageLimitException(Instant periodEnd) {
        super("USAGE_LIMIT_REACHED");
        this.periodEnd = periodEnd;
    }
    public Instant getPeriodEnd() { return periodEnd; }
}
