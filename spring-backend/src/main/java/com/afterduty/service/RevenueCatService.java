package com.afterduty.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.User;
import com.afterduty.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RevenueCat is the integration that fronts Apple App Store IAP + Google Play
 * Billing for our native apps. The web flow stays on Stripe; both rails write
 * the same {@link User#getSubscriptionExpiresAt()} so downstream gating
 * (hasActiveSubscription, the $4 cap exemption, requireActiveSubscription)
 * doesn't care which platform paid.
 *
 * <p>We trigger entitlement writes from two paths:
 * <ul>
 *   <li><b>Webhook</b> — RevenueCat → POST /api/subscription/revenuecat/webhook
 *       on every INITIAL_PURCHASE / RENEWAL / PRODUCT_CHANGE / UNCANCELLATION
 *       (and logs CANCELLATION / EXPIRATION). Authorized via a shared bearer
 *       token configured in the RC dashboard.</li>
 *   <li><b>/sync (REST pull)</b> — the app calls
 *       POST /api/subscription/revenuecat/sync right after a successful native
 *       purchase, so Pro reflects instantly without waiting for the webhook
 *       round-trip.</li>
 * </ul>
 *
 * <p>The app-user-id we send to RevenueCat is the Firebase UID, so
 * {@code subscriber.firebase_uid} matches our {@link User#getFirebaseUid()}.
 */
@Service
public class RevenueCatService {

    private static final Logger log = LoggerFactory.getLogger(RevenueCatService.class);

    private static final String RC_SUBSCRIBERS_URL = "https://api.revenuecat.com/v1/subscribers/";

    public static final String SOURCE_APPLE = "apple";
    public static final String SOURCE_GOOGLE = "google";

    private final UserRepository userRepository;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${va-claim.revenuecat.webhook-auth:}")
    private String webhookAuth;

    @Value("${va-claim.revenuecat.api-key:}")
    private String apiKey;

    @Value("${va-claim.revenuecat.entitlement-id:pro}")
    private String entitlementId;

    @Autowired
    public RevenueCatService(UserRepository userRepository) {
        this(userRepository, HttpClient.newHttpClient());
    }

    /** Visible for tests — lets a fake HttpClient stand in for the RC REST API. */
    RevenueCatService(UserRepository userRepository, HttpClient httpClient) {
        this.userRepository = userRepository;
        this.httpClient = httpClient;
    }

    public boolean isWebhookConfigured() {
        return webhookAuth != null && !webhookAuth.isBlank();
    }

    public boolean isApiConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Verify the webhook bearer + parse the event + (for purchase/renewal-shaped
     * events) write {@code subscriptionExpiresAt} and {@code subscriptionSource}
     * on the user. Cancellation / expiration events are logged only — like the
     * Stripe handler, we let the paid period run naturally.
     *
     * @throws IllegalStateException if the webhook auth secret isn't configured.
     * @throws SecurityException if the {@code Authorization} header doesn't match.
     */
    public Map<String, Object> handleWebhook(String payload, String authHeader) throws Exception {
        if (!isWebhookConfigured()) {
            throw new IllegalStateException("RevenueCat webhook auth not configured");
        }
        if (!authorizedHeader(authHeader)) {
            throw new SecurityException("invalid_auth");
        }

        JsonNode root = objectMapper.readTree(payload);
        JsonNode event = root.path("event");
        String type = event.path("type").asText("");
        String appUserId = textOrNull(event, "app_user_id");
        if (appUserId == null) appUserId = textOrNull(event, "original_app_user_id");
        String store = textOrNull(event, "store"); // APP_STORE | PLAY_STORE
        Long expirationAtMs = event.path("expiration_at_ms").isNumber()
                ? event.path("expiration_at_ms").asLong()
                : null;

        log.info("RC webhook received: type={} app_user_id={} store={}", type, appUserId, store);

        switch (type) {
            case "INITIAL_PURCHASE":
            case "RENEWAL":
            case "PRODUCT_CHANGE":
            case "UNCANCELLATION":
                return applyExpiry(appUserId, store, expirationAtMs, type);

            case "CANCELLATION":
            case "EXPIRATION":
                // Mirror Stripe's deletion handler: let the paid period run; just log.
                log.info("RC {} for app_user_id={} (paid period runs to its end)", type, appUserId);
                return Map.of("status", "cancelled_logged");

            case "BILLING_ISSUE":
                log.warn("RC BILLING_ISSUE for app_user_id={} — RC will retry; no action", appUserId);
                return Map.of("status", "logged");

            default:
                return Map.of("status", "ignored", "reason", "unhandled:" + type);
        }
    }

    private Map<String, Object> applyExpiry(String appUserId, String store, Long expirationAtMs, String type) {
        if (appUserId == null || expirationAtMs == null) {
            return Map.of("status", "ignored", "reason", "missing_fields");
        }
        User user = userRepository.findByFirebaseUid(appUserId).orElse(null);
        if (user == null) {
            // First-purchase webhook can race ahead of the user row in rare cases.
            // We can't create a User without auth context; log + skip. The /sync
            // call from the app will catch up once the user is known.
            log.info("RC {} for unknown firebase_uid={}", type, appUserId);
            return Map.of("status", "ignored", "reason", "unknown_user");
        }
        Instant expiry = Instant.ofEpochMilli(expirationAtMs);
        user.setSubscriptionExpiresAt(expiry);
        String source = mapStore(store);
        if (source != null) user.setSubscriptionSource(source);
        userRepository.save(user);
        log.info("RC {} applied: user={} expires={} source={}", type, user.getId(), expiry, source);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "synced");
        body.put("user_id", user.getId());
        body.put("expires_at", expiry.toString());
        if (source != null) body.put("source", source);
        return body;
    }

    /**
     * Pull the canonical subscriber view from RevenueCat (v1 REST) and copy the
     * {@code pro} entitlement's expiry/store onto the user. Called by
     * {@code POST /api/subscription/revenuecat/sync} immediately after a native
     * purchase so the UI doesn't have to wait for the webhook round-trip.
     */
    public Map<String, Object> syncForUser(User user) throws Exception {
        if (!isApiConfigured()) {
            throw new IllegalStateException("RevenueCat api-key not configured");
        }
        String fbUid = user.getFirebaseUid();
        if (fbUid == null || fbUid.isBlank()) {
            return Map.of("status", "ignored", "reason", "no_firebase_uid");
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(RC_SUBSCRIBERS_URL + fbUid))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.warn("RC subscriber fetch failed: status={} body={}", resp.statusCode(), resp.body());
            return Map.of("status", "rc_error", "code", resp.statusCode());
        }

        JsonNode subscriber = objectMapper.readTree(resp.body()).path("subscriber");
        JsonNode entitlement = subscriber.path("entitlements").path(entitlementId);
        if (entitlement.isMissingNode() || entitlement.isNull()) {
            return Map.of("status", "no_entitlement");
        }
        String expiresIso = textOrNull(entitlement, "expires_date");
        if (expiresIso == null) return Map.of("status", "no_expiry");

        Instant expiry = Instant.parse(expiresIso);
        user.setSubscriptionExpiresAt(expiry);
        String source = mapStore(textOrNull(entitlement, "store"));
        if (source != null) user.setSubscriptionSource(source);
        userRepository.save(user);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "synced");
        body.put("expires_at", expiry.toString());
        if (source != null) body.put("source", source);
        return body;
    }

    private boolean authorizedHeader(String header) {
        if (header == null || header.isBlank()) return false;
        // Accept both "Bearer <token>" and the bare token, since RevenueCat's
        // webhook auth field is free-form — operators sometimes paste the
        // token without the Bearer prefix.
        if (header.equals(webhookAuth)) return true;
        return header.equals("Bearer " + webhookAuth);
    }

    /** Maps the RC store identifier ("APP_STORE" / "PLAY_STORE") to our
     *  short form ("apple" / "google"); returns null for unknowns. */
    static String mapStore(String store) {
        if (store == null) return null;
        return switch (store) {
            case "APP_STORE", "MAC_APP_STORE" -> SOURCE_APPLE;
            case "PLAY_STORE" -> SOURCE_GOOGLE;
            default -> null;
        };
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText();
        return s.isEmpty() ? null : s;
    }
}
