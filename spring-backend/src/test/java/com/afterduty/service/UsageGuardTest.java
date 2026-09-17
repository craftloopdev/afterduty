package com.afterduty.service;

import com.afterduty.config.UsageProperties;
import com.afterduty.dto.UsageResponse;
import com.afterduty.exception.UsageLimitException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UsageGuardTest {

    private UsageGuard guardWith(boolean atLimit, boolean enabled) {
        UsageService usage = mock(UsageService.class);
        when(usage.getCurrentUsage(1L)).thenReturn(
                new UsageResponse(atLimit ? 100 : 50, atLimit,
                        Instant.parse("2026-04-01T00:00:00Z"),
                        Instant.parse("2026-05-01T00:00:00Z")));
        UsageProperties props = new UsageProperties();
        props.setEnabled(enabled);
        return new UsageGuard(usage, props);
    }

    @Test
    void underLimit_passes() {
        guardWith(false, true).assertCapacity(1L);
    }

    @Test
    void atLimit_throws() {
        UsageGuard g = guardWith(true, true);
        assertThatThrownBy(() -> g.assertCapacity(1L))
                .isInstanceOf(UsageLimitException.class)
                .hasMessageContaining("USAGE_LIMIT_REACHED");
    }

    @Test
    void disabled_neverThrows() {
        guardWith(true, false).assertCapacity(1L);
    }
}
