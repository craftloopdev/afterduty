package com.afterduty.service;

import com.afterduty.config.SubscriptionProperties;
import com.afterduty.model.User;
import org.springframework.stereotype.Component;

/**
 * The ONE place that answers "is this user entitled to Pro right now?" — a real
 * active subscription (any rail: Stripe / Apple / Google, all writing
 * {@link User#getSubscriptionExpiresAt()}) OR a reviewer-demo allowlisted email.
 *
 * <p>Before this, every gate called {@link User#hasActiveSubscription()} directly,
 * which only knows the stored expiry. The App-Review reviewer-demo allowlist
 * (capacitor-ios-spec §H.7.2) needs a COMPUTED Pro entitlement — no DB write, no
 * {@code subscription_source} mutation, instantly revocable — so the reviewer can
 * exercise Pro features without a sandbox-purchase dependency. Routing the gate
 * points through {@link #isPro(User)} adds that allowlist in one spot.
 *
 * <p>Inert in production: {@link SubscriptionProperties#getDemoEmails()} defaults
 * empty, so {@code isPro} is exactly {@code hasActiveSubscription()} until the
 * {@code SUBSCRIPTION_DEMO_EMAILS} env is staffed with a demo account. The AI
 * spend cap is handled separately by {@code usage.unlimited-emails} — pair the
 * same demo email there so a reviewer never stalls mid-review.
 */
@Component
public class SubscriptionAccess {

    private final SubscriptionProperties props;

    public SubscriptionAccess(SubscriptionProperties props) {
        this.props = props;
    }

    /**
     * True when the user is entitled to Pro: a real active subscription, or a
     * reviewer-demo allowlisted email. Null user → false.
     */
    public boolean isPro(User user) {
        if (user == null) return false;
        if (user.hasActiveSubscription()) return true;
        return props.isDemoEmail(user.getEmail());
    }
}
