package com.afterduty.config;

import com.afterduty.exception.UsageLimitException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-10 — the usage-cap wire shape. Both failure modes used to be 402, and the BFF maps
 * ANY 402 to "subscription required" — so a PAYING veteran at the $4/mo cap got an
 * unactionable upsell/red-bubble loop until month reset. The cap is quota, not payment:
 * <ul>
 *   <li>usage cap → {@code 429 {code: USAGE_LIMIT_REACHED, resumesAt: <ISO instant>}}</li>
 *   <li>subscription gate → {@code 402 subscription_required} (unchanged, elsewhere)</li>
 * </ul>
 */
@Tag("regression")
class RestExceptionHandlerUsageLimitTest {

    private final RestExceptionHandler handler = new RestExceptionHandler();

    @Test
    void usageCap_is429_withCodeAndResumesAt_neverThePayment402() {
        Instant periodEnd = Instant.parse("2026-08-01T00:00:00Z");

        ResponseEntity<Map<String, Object>> response =
                handler.handleUsageLimit(new UsageLimitException(periodEnd));

        // Distinguishable on status alone: 429, NOT the subscription 402.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.PAYMENT_REQUIRED);

        // And on body: a machine-readable code plus the ISO-8601 resume instant so the
        // client can render "chat resumes <date>".
        assertThat(response.getBody())
                .containsEntry("code", "USAGE_LIMIT_REACHED")
                .containsEntry("resumesAt", "2026-08-01T00:00:00Z")
                // legacy key kept for readers of the old body shape
                .containsEntry("periodEnd", "2026-08-01T00:00:00Z");
    }
}
