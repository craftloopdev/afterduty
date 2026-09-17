package com.afterduty.service;

import com.afterduty.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link StripeService#resolveTierForPriceId(String)}.
 *
 * <p>Covers the duplicate-subscription bug — legacy {@code STRIPE_PRICE_ID}
 * subscriptions must resolve to "monthly" rather than null, otherwise the
 * /upgrade page renders Subscribe CTAs for an already-paying user.
 */
@Tag("regression")
class StripePriceTierResolutionTest {

    private StripeService service;

    @BeforeEach
    void setUp() {
        service = new StripeService(Mockito.mock(UserRepository.class));
        ReflectionTestUtils.setField(service, "priceIdMonthly", "price_monthly_123");
        ReflectionTestUtils.setField(service, "priceIdAnnual",  "price_annual_456");
        ReflectionTestUtils.setField(service, "priceIdLegacy",  "price_legacy_789");
    }

    @Test
    void monthlyPriceResolvesToMonthly() {
        assertEquals("monthly", service.resolveTierForPriceId("price_monthly_123"));
    }

    @Test
    void annualPriceResolvesToAnnual() {
        assertEquals("annual", service.resolveTierForPriceId("price_annual_456"));
    }

    @Test
    void legacyPriceResolvesToMonthly() {
        // This is the bug fix — pre-split subscribers were getting null,
        // which made /upgrade show Subscribe and led to duplicate subs.
        assertEquals("monthly", service.resolveTierForPriceId("price_legacy_789"));
    }

    @Test
    void unmappedPriceResolvesToNull() {
        // currentTierForUser handles the null by defaulting to monthly +
        // logging — verified by the integration tests in
        // SubscriptionStatusCurrentTierTest. Here we only assert the helper
        // contract: unknown = null.
        assertNull(service.resolveTierForPriceId("price_unknown_xyz"));
    }

    @Test
    void nullPriceResolvesToNull() {
        assertNull(service.resolveTierForPriceId(null));
    }

    @Test
    void blankLegacyPriceIsIgnored() {
        ReflectionTestUtils.setField(service, "priceIdLegacy", "");
        // Even the empty string isn't a match.
        assertNull(service.resolveTierForPriceId(""));
    }
}
