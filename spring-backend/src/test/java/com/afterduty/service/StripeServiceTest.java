package com.afterduty.service;

import com.afterduty.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Unit tests for the bits of {@link StripeService} that don't require a
 *  running Stripe — i.e. tier-to-price mapping and plan metadata. */
class StripeServiceTest {

    StripeService stripe;

    @BeforeEach
    void setUp() {
        stripe = new StripeService(mock(UserRepository.class));
        ReflectionTestUtils.setField(stripe, "priceIdMonthly", "price_monthly_test");
        ReflectionTestUtils.setField(stripe, "priceIdAnnual",  "price_annual_test");
    }

    @Test
    void priceIdForTier_monthly_returnsMonthlyPrice() {
        assertThat(stripe.priceIdForTier("monthly")).isEqualTo("price_monthly_test");
    }

    @Test
    void priceIdForTier_annual_returnsAnnualPrice() {
        assertThat(stripe.priceIdForTier("annual")).isEqualTo("price_annual_test");
    }

    @Test
    void priceIdForTier_isCaseInsensitive() {
        assertThat(stripe.priceIdForTier("MONTHLY")).isEqualTo("price_monthly_test");
        assertThat(stripe.priceIdForTier("Annual")).isEqualTo("price_annual_test");
    }

    @Test
    void priceIdForTier_unknownTier_throws() {
        assertThatThrownBy(() -> stripe.priceIdForTier("weekly"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown subscription tier 'weekly'");
    }

    @Test
    void describePlans_returnsBothTiersWithExpectedShape() {
        @SuppressWarnings("unchecked")
        var plans = stripe.describePlans();

        @SuppressWarnings("unchecked")
        var monthly = (java.util.Map<String, Object>) plans.get("monthly");
        @SuppressWarnings("unchecked")
        var annual = (java.util.Map<String, Object>) plans.get("annual");

        assertThat(monthly)
                .containsEntry("tier", "monthly")
                .containsEntry("price_cents", 1199)
                .containsEntry("interval", "month")
                .containsEntry("formatted", "$11.99/mo")
                .containsEntry("price_id", "price_monthly_test");

        assertThat(annual)
                .containsEntry("tier", "annual")
                .containsEntry("price_cents", 11999)
                .containsEntry("interval", "year")
                .containsEntry("formatted", "$119.99/yr")
                .containsEntry("savings", "Save $24/yr")
                .containsEntry("price_id", "price_annual_test");
    }

    @Test
    void isConfigured_falseWhenSecretKeyMissing() {
        // priceIds are set but secret-key isn't -> not configured
        assertThat(stripe.isConfigured()).isFalse();
    }

    @Test
    void isConfigured_trueWhenAllThreeSet() {
        ReflectionTestUtils.setField(stripe, "secretKey", "sk_test_anything");
        assertThat(stripe.isConfigured()).isTrue();
    }

    @Test
    void isConfigured_falseWhenAnnualPriceMissing() {
        ReflectionTestUtils.setField(stripe, "secretKey", "sk_test_anything");
        ReflectionTestUtils.setField(stripe, "priceIdAnnual", "");
        assertThat(stripe.isConfigured()).isFalse();
    }

    // ---- syncCustomerEmail guards (no Stripe call is ever attempted) ---------
    // The real update path needs the live API; these pin the early returns that
    // keep the method a safe no-op on the auth attach path.

    @Test
    void syncCustomerEmail_noCustomer_noOp() {
        com.afterduty.model.User user = new com.afterduty.model.User();
        user.setEmail("vet@example.com");
        stripe.syncCustomerEmail(user); // no customer id → returns before any API touch
    }

    @Test
    void syncCustomerEmail_syntheticEmail_noOp() {
        com.afterduty.model.User user = new com.afterduty.model.User();
        user.setStripeCustomerId("cus_123");
        user.setEmail("uid@firebase.local"); // placeholder never reaches Stripe
        stripe.syncCustomerEmail(user);
    }

    @Test
    void syncCustomerEmail_unconfigured_noOp() {
        com.afterduty.model.User user = new com.afterduty.model.User();
        user.setStripeCustomerId("cus_123");
        user.setEmail("vet@example.com");
        stripe.syncCustomerEmail(user); // no secret key in unit tests → isConfigured() false
    }
}
