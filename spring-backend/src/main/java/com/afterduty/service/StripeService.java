package com.afterduty.service;

import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerUpdateParams;
import com.stripe.param.SubscriptionListParams;
import com.stripe.param.checkout.SessionCreateParams;
import com.afterduty.model.User;
import com.afterduty.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stripe integration — Pro subscriptions at $11.99/mo or $119.99/yr (recurring).
 *
 * <p>The veteran picks a tier on the upgrade page, we create a Stripe
 * Checkout Session in {@code subscription} mode pointed at the matching
 * recurring price, and Stripe redirects them through hosted checkout.
 * On {@code checkout.session.completed} (and on every subsequent renewal
 * via {@code customer.subscription.updated} / {@code invoice.paid}) we
 * refresh {@code User.subscriptionExpiresAt} from the subscription's
 * {@code current_period_end}. Cancellation lets the period run out
 * naturally; {@code customer.subscription.deleted} is logged for audit.
 *
 * <p>An {@code /admin/grant} escape hatch (separate from Stripe) still
 * adds N days when Stripe didn't fire — useful for manual cash payments
 * or webhook-version drift.
 */
@Service
public class StripeService {

    public static final String TIER_MONTHLY = "monthly";
    public static final String TIER_ANNUAL = "annual";

    private static final Logger log = LoggerFactory.getLogger(StripeService.class);

    private final UserRepository userRepository;

    @Value("${va-claim.stripe.secret-key:}")
    private String secretKey;

    @Value("${va-claim.stripe.webhook-secret:}")
    private String webhookSecret;

    @Value("${va-claim.stripe.price-id-monthly:}")
    private String priceIdMonthly;

    @Value("${va-claim.stripe.price-id-annual:}")
    private String priceIdAnnual;

    // Legacy single price (one-tier, pre-split, originally $10/mo) from before
    // we split into monthly/annual. Subs created against this price ID still
    // exist for early users — match it as monthly so they don't appear as Free
    // and get pushed into a second Subscribe checkout.
    @Value("${va-claim.stripe.price-id:}")
    private String priceIdLegacy;

    @Value("${va-claim.stripe.checkout-success-url:}")
    private String successUrl;

    @Value("${va-claim.stripe.checkout-cancel-url:}")
    private String cancelUrl;

    @Value("${va-claim.stripe.admin-grant-days:365}")
    private int adminGrantDays;

    @Value("${va-claim.stripe.portal-return-url:https://app.afterduty.app/upgrade}")
    private String portalReturnUrl;

    public StripeService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @PostConstruct
    void init() {
        if (secretKey != null && !secretKey.isBlank()) {
            Stripe.apiKey = secretKey;
            log.info("Stripe configured (test={}, monthly={}, annual={})",
                    secretKey.startsWith("sk_test_"),
                    priceIdMonthly != null && !priceIdMonthly.isBlank(),
                    priceIdAnnual != null && !priceIdAnnual.isBlank());
        } else {
            log.warn("Stripe secret key not set — subscription endpoints will error if called");
        }
    }

    public boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank()
                && priceIdMonthly != null && !priceIdMonthly.isBlank()
                && priceIdAnnual != null && !priceIdAnnual.isBlank();
    }

    public int getAdminGrantDays() {
        return adminGrantDays;
    }

    /** Resolve a tier slug ("monthly"/"annual") to its configured Stripe price id. */
    String priceIdForTier(String tier) {
        if (TIER_MONTHLY.equalsIgnoreCase(tier)) return priceIdMonthly;
        if (TIER_ANNUAL.equalsIgnoreCase(tier))  return priceIdAnnual;
        throw new IllegalArgumentException(
                "Unknown subscription tier '" + tier + "' — expected '"
                        + TIER_MONTHLY + "' or '" + TIER_ANNUAL + "'");
    }

    public Map<String, Object> createCheckoutSession(User user, String tier) throws StripeException {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "Stripe not configured (missing secret key or per-tier price ids)");
        }
        String priceId = priceIdForTier(tier);
        if (priceId == null || priceId.isBlank()) {
            throw new IllegalStateException(
                    "Stripe price id for tier '" + tier + "' is not set");
        }

        String customerId = ensureCustomer(user);

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setCustomer(customerId)
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(priceId)
                        .setQuantity(1L)
                        .build())
                .setSuccessUrl(successUrl + (successUrl.contains("?") ? "&" : "?")
                        + "session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(cancelUrl)
                .putMetadata("user_id", String.valueOf(user.getId()))
                .putMetadata("tier", tier.toLowerCase())
                .setAllowPromotionCodes(true)
                // Don't force a card when nothing is due now. A normal customer
                // still owes the full price up front (card collected as usual), but
                // a 100%-off "first month free" promo code zeroes the first invoice,
                // so Checkout skips card collection entirely — a true no-card free
                // month. With no card on file, Stripe simply can't bill at renewal,
                // so access lapses instead of producing a surprise charge.
                .setPaymentMethodCollection(
                        SessionCreateParams.PaymentMethodCollection.IF_REQUIRED)
                .build();

        Session session = Session.create(params);

        user.setLastStripeSessionId(session.getId());
        userRepository.save(user);

        return Map.of("url", session.getUrl(), "session_id", session.getId(), "tier", tier);
    }

    private String ensureCustomer(User user) throws StripeException {
        String existing = user.getStripeCustomerId();
        if (existing != null && !existing.isBlank()) {
            // Verify the stored customer still exists in the CURRENT Stripe account.
            // After a Stripe account switch (e.g. test→live, or a different account),
            // an id minted in the old account resolves to "No such customer"
            // (resource_missing) and would hard-fail checkout for every pre-migration
            // user. In that case fall through and mint a fresh customer instead.
            try {
                Customer existingCustomer = Customer.retrieve(existing);
                if (existingCustomer != null && !Boolean.TRUE.equals(existingCustomer.getDeleted())) {
                    return existing;
                }
                log.warn("Stripe customer {} is deleted; minting a fresh one for user {}",
                        existing, user.getId());
            } catch (StripeException e) {
                if (!"resource_missing".equals(e.getCode())) {
                    throw e; // a real Stripe failure (auth, rate limit) — don't mask it
                }
                log.warn("Stored Stripe customer {} not found in the current account "
                        + "(likely an account switch) — minting a fresh one for user {}",
                        existing, user.getId());
            }
        }
        // Real email only: phone-OTP accounts carry a synthetic
        // "<uid>@firebase.local" placeholder that Stripe could never deliver
        // receipts or dunning to (and that leaks the uid as contact info).
        // Stripe customers don't require an email — metadata identifies them,
        // and the email-attach flow syncs the real one on later (see
        // syncCustomerEmail).
        CustomerCreateParams.Builder builder = CustomerCreateParams.builder()
                .putMetadata("user_id", String.valueOf(user.getId()))
                .putMetadata("firebase_uid", user.getFirebaseUid() != null ? user.getFirebaseUid() : "");
        if (user.getRealEmail() != null) builder.setEmail(user.getRealEmail());
        if (realName(user) != null) builder.setName(realName(user));
        CustomerCreateParams params = builder.build();
        Customer customer = Customer.create(params);
        user.setStripeCustomerId(customer.getId());
        userRepository.save(user);
        return customer.getId();
    }

    /**
     * Push the user's real email onto their existing Stripe customer — called
     * by the email-attach flow so a phone-OTP subscriber (whose customer was
     * created email-less) becomes reachable for receipts and dunning. No-op
     * when there is no customer yet (createCustomer picks the email up) or no
     * real email. Best-effort by contract: callers sit on the auth attach path,
     * so failures are logged, never thrown.
     */
    public void syncCustomerEmail(User user) {
        String customerId = user.getStripeCustomerId();
        String email = user.getRealEmail();
        if (customerId == null || customerId.isBlank() || email == null || !isConfigured()) return;
        try {
            Customer customer = Customer.retrieve(customerId);
            CustomerUpdateParams.Builder update = CustomerUpdateParams.builder().setEmail(email);
            // The user has usually named themselves by now — carry it along.
            if (realName(user) != null) update.setName(realName(user));
            customer.update(update.build());
            log.info("Synced real email onto Stripe customer {} for user {}", customerId, user.getId());
        } catch (StripeException e) {
            log.warn("Could not sync email onto Stripe customer {} for user {} — "
                    + "customer stays email-less (no retry)", customerId, user.getId(), e);
        }
    }

    /**
     * The user's name, or null when it's the uid-derived default: a no-email
     * Firebase signup gets {@code name = local-part of "<uid>@firebase.local"}
     * (FirebaseAuthService), which is an internal id, not contact info — the
     * same rule as {@link User#getRealEmail()}, applied to the name column.
     */
    private static String realName(User user) {
        String name = user.getName();
        if (name == null || name.isBlank()) return null;
        return name.equals(user.getFirebaseUid()) ? null : name;
    }

    /**
     * Admin-only escape hatch that adds {@link #adminGrantDays} of access
     * without going through Stripe. Used when a manual payment was taken
     * outside the normal flow or when the webhook version-skew failed.
     */
    public Instant grantSubscription(User user) {
        Instant now = Instant.now();
        Instant base = (user.getSubscriptionExpiresAt() != null && user.getSubscriptionExpiresAt().isAfter(now))
                ? user.getSubscriptionExpiresAt()
                : now;
        Instant newExpiry = base.plus(adminGrantDays, ChronoUnit.DAYS);
        user.setSubscriptionExpiresAt(newExpiry);
        userRepository.save(user);
        log.info("Granted subscription to user {} until {}", user.getId(), newExpiry);
        return newExpiry;
    }

    /**
     * Verify and act on a Stripe webhook. Handles the recurring-subscription
     * event set: {@code checkout.session.completed} kicks off the first
     * period; {@code customer.subscription.updated} and {@code invoice.paid}
     * extend it on every renewal; {@code customer.subscription.deleted}
     * logs the cancellation (we let the paid-through period run out);
     * {@code invoice.payment_failed} logs for monitoring (Stripe retries
     * automatically before triggering the deletion event).
     */
    public Map<String, Object> handleWebhook(String payload, String sigHeader) throws SignatureVerificationException {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException("Webhook secret not configured");
        }

        Event event = Webhook.constructEvent(payload, sigHeader, webhookSecret);

        log.info("Stripe webhook received: id={} type={}", event.getId(), event.getType());

        switch (event.getType()) {
            case "checkout.session.completed":
                return handleCheckoutCompleted(event, payload);
            case "customer.subscription.updated":
            case "customer.subscription.created":
                return handleSubscriptionUpserted(event, payload);
            case "invoice.paid":
                return handleInvoicePaid(event, payload);
            case "customer.subscription.deleted":
                return handleSubscriptionDeleted(event, payload);
            case "invoice.payment_failed":
                return handleInvoicePaymentFailed(event, payload);
            default:
                return Map.of("status", "ignored", "reason", "unhandled:" + event.getType());
        }
    }

    private Map<String, Object> handleCheckoutCompleted(Event event, String payload) {
        // SDK-based deserialization returns empty on API-version drift —
        // fall back to raw JSON parsing, which is version-agnostic.
        Session session = (Session) event.getDataObjectDeserializer().getObject().orElse(null);
        String paymentStatus;
        String userIdStr;
        String email;
        String customerId;
        String subscriptionId;

        if (session != null) {
            paymentStatus = session.getPaymentStatus();
            userIdStr = session.getMetadata() != null ? session.getMetadata().get("user_id") : null;
            email = session.getCustomerDetails() != null ? session.getCustomerDetails().getEmail() : null;
            customerId = session.getCustomer();
            subscriptionId = session.getSubscription();
        } else {
            log.warn("Stripe SDK could not deserialize session for event {} — falling back to raw JSON", event.getId());
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
                com.fasterxml.jackson.databind.JsonNode obj = node.path("data").path("object");
                paymentStatus = obj.path("payment_status").asText(null);
                userIdStr = obj.path("metadata").path("user_id").asText(null);
                email = obj.path("customer_details").path("email").asText(null);
                customerId = obj.path("customer").asText(null);
                subscriptionId = obj.path("subscription").asText(null);
            } catch (Exception e) {
                log.error("Fallback JSON parse failed: {}", e.getMessage());
                return Map.of("status", "ignored", "reason", "parse_failed");
            }
        }

        // Subscription-mode sessions report payment_status=paid OR
        // payment_status=no_payment_required (e.g. trials). Either is fine.
        if (paymentStatus != null && !"paid".equals(paymentStatus)
                && !"no_payment_required".equals(paymentStatus)) {
            log.info("Ignoring checkout.session.completed — payment_status={}", paymentStatus);
            return Map.of("status", "ignored", "reason", "unpaid:" + paymentStatus);
        }

        User u = resolveUser(userIdStr, email);
        if (u == null) {
            return userIdStr == null && email != null
                    ? Map.of("status", "pending_signup", "email", email)
                    : Map.of("status", "ignored", "reason", "no_match");
        }
        if (customerId != null && u.getStripeCustomerId() == null) {
            u.setStripeCustomerId(customerId);
        }

        Instant expiry = applySubscriptionExpiry(u, subscriptionId);
        userRepository.save(u);

        return Map.of(
                "status", "activated",
                "user_id", u.getId(),
                "subscription_id", subscriptionId == null ? "" : subscriptionId,
                "expires_at", expiry == null ? "" : expiry.toString());
    }

    private Map<String, Object> handleSubscriptionUpserted(Event event, String payload) {
        String customerId;
        String subscriptionId;
        Long currentPeriodEnd;

        // The event is rendered at the account's API version, which may differ
        // from the SDK's pinned version — so getObject() can return empty. Pull
        // the ids from the SDK object when present, else straight from the raw
        // payload, and recover the period via a fresh fetch below (don't rely on
        // the event body, where current_period_end may be on the item / absent).
        Subscription sub = (Subscription) event.getDataObjectDeserializer().getObject().orElse(null);
        if (sub != null) {
            customerId = sub.getCustomer();
            subscriptionId = sub.getId();
        } else {
            try {
                com.fasterxml.jackson.databind.JsonNode obj = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(payload).path("data").path("object");
                customerId = obj.path("customer").asText(null);
                subscriptionId = obj.path("id").asText(null);
            } catch (Exception e) {
                log.error("Fallback JSON parse failed for subscription event: {}", e.getMessage());
                return Map.of("status", "ignored", "reason", "parse_failed");
            }
        }

        User u = userRepository.findByStripeCustomerId(customerId).orElse(null);
        if (u == null) {
            log.info("Subscription event {} for unknown customer {}", subscriptionId, customerId);
            return Map.of("status", "ignored", "reason", "unknown_customer");
        }

        currentPeriodEnd = readCurrentPeriodEnd(sub, payload);
        if (currentPeriodEnd == null && subscriptionId != null && !subscriptionId.isBlank()) {
            try {
                currentPeriodEnd = readCurrentPeriodEnd(Subscription.retrieve(subscriptionId), null);
            } catch (StripeException e) {
                log.warn("subscription event: could not retrieve {} ({})", subscriptionId, e.getMessage());
            }
        }

        Instant expiry = currentPeriodEnd != null ? Instant.ofEpochSecond(currentPeriodEnd) : null;
        if (expiry != null) {
            Instant existing = u.getSubscriptionExpiresAt();
            if (existing == null || expiry.isAfter(existing)) {
                u.setSubscriptionExpiresAt(expiry);
                userRepository.save(u);
            }
        }
        log.info("Subscription {} upserted for user {} → expires {}",
                subscriptionId, u.getId(), expiry);
        return Map.of(
                "status", "synced",
                "user_id", u.getId(),
                "subscription_id", subscriptionId == null ? "" : subscriptionId,
                "expires_at", expiry == null ? "" : expiry.toString());
    }

    private Map<String, Object> handleInvoicePaid(Event event, String payload) {
        String customerId;
        String subscriptionId = null;
        Long periodEnd = null;

        Invoice invoice = (Invoice) event.getDataObjectDeserializer().getObject().orElse(null);
        if (invoice != null) {
            customerId = invoice.getCustomer();
            // Stripe SDK 29 moved invoice.subscription onto
            // parent.subscriptionDetails.subscription. parent is null on
            // non-subscription invoices, so guard the chain.
            try {
                if (invoice.getParent() != null
                        && invoice.getParent().getSubscriptionDetails() != null) {
                    subscriptionId = invoice.getParent().getSubscriptionDetails().getSubscription();
                }
            } catch (Throwable ignore) { /* leave subscriptionId null */ }
            periodEnd = invoice.getPeriodEnd();
        } else {
            try {
                com.fasterxml.jackson.databind.JsonNode obj = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(payload).path("data").path("object");
                customerId = obj.path("customer").asText(null);
                subscriptionId = obj.path("subscription").asText(null);
                periodEnd = obj.path("period_end").isNumber() ? obj.path("period_end").asLong() : null;
            } catch (Exception e) {
                log.error("Fallback JSON parse failed for invoice.paid: {}", e.getMessage());
                return Map.of("status", "ignored", "reason", "parse_failed");
            }
        }

        User u = userRepository.findByStripeCustomerId(customerId).orElse(null);
        if (u == null) {
            return Map.of("status", "ignored", "reason", "unknown_customer");
        }
        // Prefer the SUBSCRIPTION's current_period_end (authoritative for both the
        // first invoice — whose own period_end can be the creation instant — and
        // renewals). Fall back to the invoice's period_end only if the subscription
        // can't be read. Never SHORTEN an existing expiry: the paid-through period
        // must run out, and cancellation is handled separately.
        Long effectiveEnd = periodEnd;
        if (subscriptionId != null && !subscriptionId.isBlank()) {
            try {
                Long subEnd = readCurrentPeriodEnd(Subscription.retrieve(subscriptionId), null);
                if (subEnd != null) effectiveEnd = subEnd;
            } catch (StripeException e) {
                log.warn("invoice.paid: could not retrieve subscription {} ({}); using invoice period",
                        subscriptionId, e.getMessage());
            }
        }
        if (effectiveEnd != null) {
            Instant expiry = Instant.ofEpochSecond(effectiveEnd);
            Instant existing = u.getSubscriptionExpiresAt();
            if (existing == null || expiry.isAfter(existing)) {
                u.setSubscriptionExpiresAt(expiry);
                userRepository.save(u);
                log.info("invoice.paid for user {} → period_end {}", u.getId(), expiry);
                return Map.of("status", "synced", "user_id", u.getId(),
                        "subscription_id", subscriptionId == null ? "" : subscriptionId,
                        "expires_at", expiry.toString());
            }
            log.info("invoice.paid for user {}: {} not later than existing {} — keeping existing",
                    u.getId(), expiry, existing);
            return Map.of("status", "ack", "user_id", u.getId());
        }
        log.info("invoice.paid for user {} (no period_end — relying on subscription.updated)", u.getId());
        return Map.of("status", "ack", "user_id", u.getId());
    }

    private Map<String, Object> handleSubscriptionDeleted(Event event, String payload) {
        String customerId;
        String subscriptionId;
        Subscription sub = (Subscription) event.getDataObjectDeserializer().getObject().orElse(null);
        if (sub != null) {
            customerId = sub.getCustomer();
            subscriptionId = sub.getId();
        } else {
            try {
                com.fasterxml.jackson.databind.JsonNode obj = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(payload).path("data").path("object");
                customerId = obj.path("customer").asText(null);
                subscriptionId = obj.path("id").asText(null);
            } catch (Exception e) {
                return Map.of("status", "ignored", "reason", "parse_failed");
            }
        }
        User u = userRepository.findByStripeCustomerId(customerId).orElse(null);
        if (u == null) return Map.of("status", "ignored", "reason", "unknown_customer");
        // Don't shorten subscriptionExpiresAt — Stripe lets the paid-through
        // period run to its end. The next sync (subscription.updated above
        // already happened with cancel_at_period_end=true) is what tells us
        // the user has cancelled. We just log here.
        log.info("Subscription {} deleted for user {} — paid period runs to {}",
                subscriptionId, u.getId(), u.getSubscriptionExpiresAt());
        return Map.of("status", "cancelled_logged", "user_id", u.getId(),
                "subscription_id", subscriptionId == null ? "" : subscriptionId);
    }

    private Map<String, Object> handleInvoicePaymentFailed(Event event, String payload) {
        String customerId;
        Invoice invoice = (Invoice) event.getDataObjectDeserializer().getObject().orElse(null);
        if (invoice != null) {
            customerId = invoice.getCustomer();
        } else {
            try {
                customerId = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(payload).path("data").path("object")
                        .path("customer").asText(null);
            } catch (Exception e) {
                return Map.of("status", "ignored", "reason", "parse_failed");
            }
        }
        User u = userRepository.findByStripeCustomerId(customerId).orElse(null);
        if (u == null) return Map.of("status", "ignored", "reason", "unknown_customer");
        log.warn("invoice.payment_failed for user {} — Stripe will retry; no action taken yet", u.getId());
        return Map.of("status", "logged", "user_id", u.getId());
    }

    /**
     * Look up the subscription created by checkout and use its
     * {@code current_period_end} as the user's expiry. Falls back to a
     * one-period grant from {@code now} if Stripe doesn't return the
     * subscription cleanly (very rare — webhook would still resync later).
     */
    private Instant applySubscriptionExpiry(User user, String subscriptionId) {
        if (subscriptionId == null || subscriptionId.isBlank()) {
            log.warn("checkout.session.completed for user {} had no subscription id", user.getId());
            return null;
        }
        try {
            Subscription sub = Subscription.retrieve(subscriptionId);
            Long periodEnd = readCurrentPeriodEnd(sub, null);
            if (periodEnd == null) {
                log.warn("Subscription {} had no current_period_end", subscriptionId);
                return null;
            }
            Instant expiry = Instant.ofEpochSecond(periodEnd);
            user.setSubscriptionExpiresAt(expiry);
            return expiry;
        } catch (StripeException e) {
            log.warn("Could not retrieve subscription {} for user {}: {}",
                    subscriptionId, user.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * Read {@code current_period_end} off a Subscription. The Stripe Java
     * SDK exposes it as a top-level {@code Long} via
     * {@link Subscription#getCurrentPeriodEnd()} on every published version
     * to date, so the SDK path almost always succeeds. The raw-JSON fallback
     * exists for two edge cases: events delivered with an API version newer
     * than the SDK was built against (rare), and {@code subscription_items[].
     * current_period_end} on Stripe's newer 2024-09-30+ API where the field
     * is mirrored onto each item.
     */
    private Long readCurrentPeriodEnd(Subscription sub, String rawPayload) {
        // SDK top-level getter (legacy API where the field is on the subscription).
        Long v = longGetter(sub, "getCurrentPeriodEnd");
        if (v != null) return v;
        // SDK item getter — on Stripe's 2024-09-30+ API the period moved onto each item.
        v = sdkItemPeriodEnd(sub);
        if (v != null) return v;
        // Raw event payload: data.object.current_period_end, then items[0].current_period_end.
        if (rawPayload != null) {
            try {
                v = nodePeriodEnd(new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(rawPayload).path("data").path("object"));
                if (v != null) return v;
            } catch (Exception ignore) {
                // fall through
            }
        }
        // Bulletproof last resort: re-fetch the subscription and read items[0].current_period_end
        // off its raw JSON body (the field reliably lives on the item there).
        try {
            String subId = sub != null ? sub.getId() : null;
            if (subId != null && !subId.isBlank()) {
                Subscription fresh = Subscription.retrieve(subId);
                v = sdkItemPeriodEnd(fresh);
                if (v != null) return v;
                if (fresh.getLastResponse() != null) {
                    v = nodePeriodEnd(new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(fresh.getLastResponse().body()));
                    if (v != null) return v;
                }
            }
        } catch (Exception ignore) {
            // fall through to null
        }
        return null;
    }

    /** current_period_end from a subscription-shaped JSON node (top-level or items[0]). */
    private Long nodePeriodEnd(com.fasterxml.jackson.databind.JsonNode obj) {
        if (obj.path("current_period_end").isNumber()) return obj.path("current_period_end").asLong();
        com.fasterxml.jackson.databind.JsonNode items = obj.path("items").path("data");
        if (items.isArray() && items.size() > 0 && items.get(0).path("current_period_end").isNumber()) {
            return items.get(0).path("current_period_end").asLong();
        }
        return null;
    }

    /** current_period_end off the first SDK subscription item (reflection-safe). */
    private Long sdkItemPeriodEnd(Subscription sub) {
        try {
            if (sub != null && sub.getItems() != null && sub.getItems().getData() != null
                    && !sub.getItems().getData().isEmpty()) {
                return longGetter(sub.getItems().getData().get(0), "getCurrentPeriodEnd");
            }
        } catch (Exception ignore) {
            // fall through
        }
        return null;
    }

    /** Invoke a zero-arg Long getter by reflection; null if absent or not a Long. */
    private Long longGetter(Object target, String getter) {
        if (target == null) return null;
        try {
            Object r = target.getClass().getMethod(getter).invoke(target);
            if (r instanceof Long l) return l;
        } catch (ReflectiveOperationException ignore) {
            // fall through
        }
        return null;
    }

    private User resolveUser(String userIdStr, String email) {
        if (userIdStr != null && !userIdStr.isBlank() && !"null".equals(userIdStr)) {
            try {
                return userRepository.findById(Long.parseLong(userIdStr)).orElse(null);
            } catch (NumberFormatException ignore) {
                log.warn("Bad user_id in metadata: {}", userIdStr);
            }
        }
        if (email != null && !email.isBlank()) {
            return userRepository.findByEmail(email).orElse(null);
        }
        return null;
    }

    /**
     * Returns the user's current active subscription tier: {@code "monthly"},
     * {@code "annual"}, or {@code null} (free / no active subscription).
     *
     * <p>Looks up the user's active Stripe subscription via
     * {@code Subscription.list(customer, status=active, limit=1)} and
     * matches the first item's price id against the configured price IDs.
     * Returns {@code null} on any exception (Stripe unconfigured, no customer,
     * network error, etc.) — the status endpoint treats this as non-fatal.
     */
    public String currentTierForUser(User user) {
        if (user.getStripeCustomerId() == null || user.getStripeCustomerId().isBlank()) {
            return null;
        }
        if (!isConfigured()) {
            return null;
        }
        try {
            SubscriptionListParams params = SubscriptionListParams.builder()
                    .setCustomer(user.getStripeCustomerId())
                    .setStatus(SubscriptionListParams.Status.ACTIVE)
                    .setLimit(1L)
                    .build();
            var subscriptions = Subscription.list(params);
            var data = subscriptions.getData();
            if (data == null || data.isEmpty()) {
                return null;
            }
            Subscription sub = data.get(0);
            var items = sub.getItems();
            if (items == null || items.getData() == null || items.getData().isEmpty()) {
                return null;
            }
            String priceId = items.getData().get(0).getPrice().getId();
            String tier = resolveTierForPriceId(priceId);
            if (tier == null) {
                // Unknown price (e.g. a promotional or one-off price id
                // that never made it into env vars). Log it so we can
                // backfill the env var if needed.
                log.warn("Active subscription {} for user {} uses unmapped price {} — defaulting to monthly",
                        sub.getId(), user.getId(), priceId);
                return TIER_MONTHLY;
            }
            return tier;
        } catch (StripeException e) {
            log.warn("Could not retrieve active subscription for user {}: {}", user.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * Maps a Stripe price id to a tier label. Package-private for tests.
     *
     * @return {@code "monthly"} or {@code "annual"} when the price matches one
     *         of the configured env vars (including legacy {@code STRIPE_PRICE_ID}
     *         which is treated as monthly). {@code null} when nothing matches
     *         — callers may choose to default to monthly to avoid showing
     *         Subscribe CTAs to an already-paying user.
     */
    String resolveTierForPriceId(String priceId) {
        if (priceId == null) return null;
        if (priceIdMonthly != null && priceIdMonthly.equals(priceId)) {
            return TIER_MONTHLY;
        }
        if (priceIdAnnual != null && priceIdAnnual.equals(priceId)) {
            return TIER_ANNUAL;
        }
        if (priceIdLegacy != null && !priceIdLegacy.isBlank()
                && priceIdLegacy.equals(priceId)) {
            return TIER_MONTHLY;
        }
        return null;
    }

    /**
     * Creates a Stripe Customer Portal session for the given user and returns
     * a map containing the portal {@code url}.
     *
     * @throws IllegalArgumentException with message {@code "no_stripe_customer"} if
     *         the user has no {@code stripeCustomerId}.
     * @throws StripeException if the Stripe API call fails (caller maps to 502).
     */
    public Map<String, Object> createPortalSession(User user) throws StripeException {
        if (user.getStripeCustomerId() == null || user.getStripeCustomerId().isBlank()) {
            throw new IllegalArgumentException("no_stripe_customer");
        }
        com.stripe.param.billingportal.SessionCreateParams params =
                com.stripe.param.billingportal.SessionCreateParams.builder()
                        .setCustomer(user.getStripeCustomerId())
                        .setReturnUrl(portalReturnUrl)
                        .build();
        com.stripe.model.billingportal.Session session =
                com.stripe.model.billingportal.Session.create(params);
        return Map.of("url", session.getUrl());
    }

    /**
     * Cancels all of the user's still-running Stripe subscriptions immediately.
     * Used by account deletion (Apple Guideline 5.1.1(v)) so a deleted user is
     * not left with a recurring Stripe charge against a customer record we are
     * about to orphan.
     *
     * <p>Best-effort: if Stripe is unconfigured, the user has no
     * {@code stripeCustomerId}, or any individual cancel fails, we log and move
     * on rather than aborting the account deletion. Apple IAP / Play Billing
     * subscriptions cannot be cancelled server-side and are intentionally not
     * touched here — those are governed by the store's own subscription UI.
     *
     * @return the number of subscriptions successfully cancelled.
     */
    public int cancelActiveSubscriptions(User user) {
        if (user == null) {
            return 0;
        }
        if (!isConfigured()) {
            log.info("cancelActiveSubscriptions: Stripe not configured — skipping for user {}", user.getId());
            return 0;
        }
        String customerId = user.getStripeCustomerId();
        if (customerId == null || customerId.isBlank()) {
            log.info("cancelActiveSubscriptions: user {} has no Stripe customer — nothing to cancel", user.getId());
            return 0;
        }

        int cancelled = 0;
        for (SubscriptionListParams.Status status :
                new SubscriptionListParams.Status[]{
                        SubscriptionListParams.Status.ACTIVE,
                        SubscriptionListParams.Status.TRIALING}) {
            try {
                SubscriptionListParams params = SubscriptionListParams.builder()
                        .setCustomer(customerId)
                        .setStatus(status)
                        .setLimit(100L)
                        .build();
                var data = Subscription.list(params).getData();
                if (data == null) {
                    continue;
                }
                for (Subscription sub : data) {
                    try {
                        sub.cancel();
                        cancelled++;
                        log.info("Cancelled Stripe subscription {} ({}) for deleted user {}",
                                sub.getId(), status, user.getId());
                    } catch (StripeException e) {
                        log.warn("Failed to cancel Stripe subscription {} for user {}: {}",
                                sub.getId(), user.getId(), e.getMessage());
                    }
                }
            } catch (StripeException e) {
                log.warn("Failed to list {} Stripe subscriptions for user {}: {}",
                        status, user.getId(), e.getMessage());
            }
        }
        return cancelled;
    }

    /** Plan metadata exposed by /api/subscription/status for the upgrade page. */
    public Map<String, Object> describePlans() {
        Map<String, Object> monthly = new LinkedHashMap<>();
        monthly.put("tier", TIER_MONTHLY);
        monthly.put("name", "Monthly");
        monthly.put("price_cents", 1199);
        monthly.put("currency", "usd");
        monthly.put("interval", "month");
        monthly.put("formatted", "$11.99/mo");
        monthly.put("price_id", priceIdMonthly);

        Map<String, Object> annual = new LinkedHashMap<>();
        annual.put("tier", TIER_ANNUAL);
        annual.put("name", "Annual");
        annual.put("price_cents", 11999);
        annual.put("currency", "usd");
        annual.put("interval", "year");
        annual.put("formatted", "$119.99/yr");
        annual.put("savings", "Save $24/yr");
        annual.put("price_id", priceIdAnnual);

        Map<String, Object> plans = new LinkedHashMap<>();
        plans.put("monthly", monthly);
        plans.put("annual", annual);
        return plans;
    }
}
