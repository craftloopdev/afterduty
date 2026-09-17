package com.afterduty.controller;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.afterduty.config.SecurityConfig;
import com.afterduty.model.User;
import com.afterduty.security.AdminCheck;
import com.afterduty.service.StripeService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/subscription")
public class SubscriptionController {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionController.class);

    private final StripeService stripeService;
    private final com.afterduty.repository.UserRepository userRepository;
    private final AdminCheck adminCheck;
    private final com.afterduty.service.RevenueCatService revenueCatService;
    private final com.afterduty.service.SubscriptionAccess subscriptionAccess;

    @Value("${va-claim.stripe.publishable-key:}")
    private String publishableKey;

    public SubscriptionController(StripeService stripeService,
                                  com.afterduty.repository.UserRepository userRepository,
                                  AdminCheck adminCheck,
                                  com.afterduty.service.RevenueCatService revenueCatService,
                                  com.afterduty.service.SubscriptionAccess subscriptionAccess) {
        this.stripeService = stripeService;
        this.userRepository = userRepository;
        this.adminCheck = adminCheck;
        this.revenueCatService = revenueCatService;
        this.subscriptionAccess = subscriptionAccess;
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        User user = getUser(request);
        Map<String, Object> body = new LinkedHashMap<>();
        // Entitlement (real subscription OR reviewer-demo allowlist) — the app's
        // whole isPro state derives from this flag.
        body.put("active", subscriptionAccess.isPro(user));
        body.put("expires_at", user.getSubscriptionExpiresAt() != null
                ? user.getSubscriptionExpiresAt().toString() : null);
        body.put("plans", stripeService.describePlans());
        body.put("features", List.of(
                Map.of(
                        "key", "evidence_extract",
                        "name", "Evidence Extract",
                        "desc", "Pulls every relevant fact from your records — no hand-reading hundreds of pages."
                ),
                Map.of(
                        "key", "condition_finder",
                        "name", "Condition Finder",
                        "desc", "Identifies every condition your evidence supports with VASRD codes and rating estimates."
                ),
                Map.of(
                        "key", "claim_strengthener",
                        "name", "Claim Strengthener",
                        "desc", "Finds the missing evidence costing you rating points — and tells you exactly how to get it."
                )
        ));
        body.put("publishable_key", publishableKey != null && !publishableKey.isBlank() ? publishableKey : null);
        body.put("current_tier", stripeService.currentTierForUser(user));
        // Which billing rail owns the subscription ("stripe" | "apple" | "google").
        // Null on free/legacy rows — jackson non_null omits it entirely.
        body.put("source", user.getSubscriptionSource());
        return body;
    }

    /**
     * Body: {@code {"tier": "monthly" | "annual"}}. Defaults to monthly when
     * the body is missing or the field is blank — matches the marketing
     * page's "default to monthly, upsell to annual" framing.
     */
    @PostMapping("/checkout")
    public Map<String, Object> checkout(@RequestBody(required = false) Map<String, Object> body,
                                        HttpServletRequest request) {
        User user = getUser(request);
        String tier = body != null && body.get("tier") != null
                ? body.get("tier").toString()
                : StripeService.TIER_MONTHLY;
        try {
            return stripeService.createCheckoutSession(user, tier);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (StripeException e) {
            log.warn("Stripe checkout create failed for user {}: {}", user.getId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Stripe checkout failed: " + e.getMessage());
        }
    }

    /**
     * Creates a Stripe Customer Portal session for the authenticated user and
     * returns {@code {"url": "..."}} pointing at the billing portal.
     *
     * <p>Returns 400 with reason {@code "no_stripe_customer"} when the user has
     * no {@code stripeCustomerId} (i.e. never started a Stripe checkout).
     * Surfaces Stripe API errors as 502.
     */
    @PostMapping("/portal")
    public Map<String, Object> portal(HttpServletRequest request) {
        User user = getUser(request);
        try {
            return stripeService.createPortalSession(user);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (StripeException e) {
            log.warn("Stripe portal create failed for user {}: {}", user.getId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Stripe portal session failed: " + e.getMessage());
        }
    }

    /**
     * Admin-only escape hatch to grant a subscription when Stripe's webhook
     * did not fire (SDK/API version drift, manual cash payment, etc.).
     * Only callable by an admin email or role=admin.
     */
    @PostMapping("/admin/grant/{userId}")
    public Map<String, Object> adminGrant(@PathVariable Long userId, HttpServletRequest request) {
        User caller = getUser(request);
        if (!adminCheck.isAdmin(caller)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin only");
        }
        return userRepository.findById(userId)
                .map(u -> {
                    var expiry = stripeService.grantSubscription(u);
                    log.info("Admin {} granted subscription to user {} until {}",
                            caller.getEmail(), u.getId(), expiry);
                    return Map.<String, Object>of(
                            "status", "granted",
                            "user_id", u.getId(),
                            "email", u.getEmail(),
                            "expires_at", expiry.toString());
                })
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    @PostMapping("/webhook")
    public Map<String, Object> webhook(@RequestBody String payload,
                                       @RequestHeader(value = "Stripe-Signature", required = false) String sig) {
        try {
            return stripeService.handleWebhook(payload, sig != null ? sig : "");
        } catch (SignatureVerificationException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid signature");
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (Exception e) {
            log.warn("Webhook processing failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * RevenueCat webhook receiver: every native subscription event (Apple IAP /
     * Google Play, fronted by RevenueCat) lands here. We verify the bearer
     * token, parse the event, and update {@code subscriptionExpiresAt} +
     * {@code subscriptionSource} on the user. Auth-bypassed in SecurityConfig
     * for the same reason as the Stripe webhook.
     */
    @PostMapping("/revenuecat/webhook")
    public Map<String, Object> revenueCatWebhook(@RequestBody String payload,
                                                 @RequestHeader(value = "Authorization", required = false) String auth) {
        try {
            return revenueCatService.handleWebhook(payload, auth);
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (Exception e) {
            log.warn("RevenueCat webhook processing failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Immediately-after-purchase sync: the Flutter app calls this after a
     * native {@code Purchases.purchasePackage} so the backend pulls the latest
     * RevenueCat subscriber view and sets the user's expiry without waiting on
     * the webhook round-trip. Returns the same shape as the webhook handler.
     */
    @PostMapping("/revenuecat/sync")
    public Map<String, Object> revenueCatSync(HttpServletRequest request) {
        User user = getUser(request);
        try {
            return revenueCatService.syncForUser(user);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } catch (Exception e) {
            log.warn("RevenueCat sync failed for user {}: {}", user.getId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "RevenueCat sync failed: " + e.getMessage());
        }
    }

    private User getUser(HttpServletRequest request) {
        User user = (User) request.getAttribute(SecurityConfig.USER_ATTRIBUTE);
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        return user;
    }
}
