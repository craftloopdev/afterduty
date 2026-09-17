package com.afterduty.service;

import com.afterduty.config.SubscriptionProperties;
import com.afterduty.model.User;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reviewer-demo Pro allowlist (capacitor-ios-spec §H.7.2) — a COMPUTED
 * entitlement layered over the stored subscription, no DB write.
 */
@Tag("regression")
class SubscriptionAccessTest {

    private static final Instant FUTURE = Instant.now().plus(365, ChronoUnit.DAYS);
    private static final Instant PAST = Instant.now().minus(1, ChronoUnit.DAYS);

    private SubscriptionAccess withDemo(String... emails) {
        SubscriptionProperties props = new SubscriptionProperties();
        props.setDemoEmails(Set.of(emails));
        return new SubscriptionAccess(props);
    }

    private User user(String email, Instant expires) {
        return User.builder().email(email).subscriptionExpiresAt(expires).build();
    }

    @Test
    void realActiveSubscription_isPro_regardlessOfAllowlist() {
        SubscriptionAccess access = withDemo(); // empty allowlist
        assertThat(access.isPro(user("vet@example.com", FUTURE))).isTrue();
    }

    @Test
    void expiredSubscription_andNotOnAllowlist_isNotPro() {
        SubscriptionAccess access = withDemo("reviewer@apple-demo.com");
        assertThat(access.isPro(user("vet@example.com", PAST))).isFalse();
        assertThat(access.isPro(user("vet@example.com", null))).isFalse();
    }

    @Test
    void demoAllowlistEmail_computesAsPro_withNoSubscription() {
        SubscriptionAccess access = withDemo("reviewer@apple-demo.com");
        // No subscription at all, but on the allowlist → Pro (case-insensitive).
        assertThat(access.isPro(user("reviewer@apple-demo.com", null))).isTrue();
        assertThat(access.isPro(user("REVIEWER@Apple-Demo.com", PAST))).isTrue();
    }

    @Test
    void emptyAllowlist_isInert_matchesHasActiveSubscriptionExactly() {
        SubscriptionAccess access = withDemo();
        assertThat(access.isPro(user("a@b.com", FUTURE))).isTrue();
        assertThat(access.isPro(user("a@b.com", PAST))).isFalse();
    }

    @Test
    void nullUser_isNotPro() {
        assertThat(withDemo("reviewer@apple-demo.com").isPro(null)).isFalse();
    }

    @Test
    void removingEmailFromAllowlist_revokesInstantly() {
        // Same property object, allowlist emptied → the demo account is no longer Pro
        // (models the operator removing SUBSCRIPTION_DEMO_EMAILS; no DB write to undo).
        SubscriptionProperties props = new SubscriptionProperties();
        props.setDemoEmails(Set.of("reviewer@apple-demo.com"));
        SubscriptionAccess access = new SubscriptionAccess(props);
        User u = user("reviewer@apple-demo.com", null);
        assertThat(access.isPro(u)).isTrue();

        props.setDemoEmails(Set.of());
        assertThat(access.isPro(u)).isFalse();
    }
}
