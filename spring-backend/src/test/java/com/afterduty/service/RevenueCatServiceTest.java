package com.afterduty.service;

import com.afterduty.model.User;
import com.afterduty.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression tests for the RevenueCat webhook handler — the native (Apple/Play)
 * counterpart to {@link StripeService#handleWebhook}. The /sync REST pull is
 * covered separately by an integration test; here we focus on the
 * event-to-entitlement mapping that doesn't need a live RC backend.
 */
@Tag("regression")
class RevenueCatServiceTest {

    UserRepository userRepository;
    RevenueCatService svc;

    static final String AUTH = "rc-secret-xyz";

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        svc = new RevenueCatService(userRepository);
        ReflectionTestUtils.setField(svc, "webhookAuth", AUTH);
        ReflectionTestUtils.setField(svc, "entitlementId", "pro");
    }

    private String payload(String type, String appUserId, String store, Long expiresAtMs) {
        StringBuilder sb = new StringBuilder("{\"event\":{");
        sb.append("\"type\":\"").append(type).append("\"");
        if (appUserId != null) sb.append(",\"app_user_id\":\"").append(appUserId).append("\"");
        if (store != null) sb.append(",\"store\":\"").append(store).append("\"");
        if (expiresAtMs != null) sb.append(",\"expiration_at_ms\":").append(expiresAtMs);
        sb.append("}}");
        return sb.toString();
    }

    @Test
    void initialPurchase_setsExpiryAndSourceApple() throws Exception {
        User u = User.builder().id(7L).email("v@example.com").name("Vet")
                .firebaseUid("fb-uid-7").build();
        when(userRepository.findByFirebaseUid("fb-uid-7")).thenReturn(Optional.of(u));

        long expiresMs = Instant.parse("2099-01-01T00:00:00Z").toEpochMilli();
        var result = svc.handleWebhook(
                payload("INITIAL_PURCHASE", "fb-uid-7", "APP_STORE", expiresMs),
                "Bearer " + AUTH);

        assertThat(result).containsEntry("status", "synced")
                .containsEntry("source", "apple");
        assertThat(u.getSubscriptionExpiresAt()).isEqualTo(Instant.ofEpochMilli(expiresMs));
        assertThat(u.getSubscriptionSource()).isEqualTo("apple");
        verify(userRepository).save(u);
    }

    @Test
    void renewal_setsExpiryAndSourceGoogle() throws Exception {
        User u = User.builder().id(8L).email("v2@example.com").name("Vet2")
                .firebaseUid("fb-uid-8").build();
        when(userRepository.findByFirebaseUid("fb-uid-8")).thenReturn(Optional.of(u));

        long expiresMs = Instant.parse("2099-06-01T00:00:00Z").toEpochMilli();
        var result = svc.handleWebhook(
                payload("RENEWAL", "fb-uid-8", "PLAY_STORE", expiresMs),
                AUTH); // also accept bare token, not just "Bearer ..."

        assertThat(result).containsEntry("status", "synced")
                .containsEntry("source", "google");
        assertThat(u.getSubscriptionSource()).isEqualTo("google");
    }

    @Test
    void uncancellation_setsExpiry() throws Exception {
        User u = User.builder().id(9L).email("v3@example.com").name("Vet3")
                .firebaseUid("fb-uid-9").build();
        when(userRepository.findByFirebaseUid("fb-uid-9")).thenReturn(Optional.of(u));
        long expiresMs = Instant.parse("2099-03-01T00:00:00Z").toEpochMilli();

        var result = svc.handleWebhook(
                payload("UNCANCELLATION", "fb-uid-9", "APP_STORE", expiresMs),
                "Bearer " + AUTH);

        assertThat(result).containsEntry("status", "synced");
        assertThat(u.getSubscriptionExpiresAt()).isEqualTo(Instant.ofEpochMilli(expiresMs));
    }

    @Test
    void missingAuthHeader_throwsSecurityException() {
        assertThatThrownBy(() -> svc.handleWebhook(
                payload("INITIAL_PURCHASE", "fb-uid", "APP_STORE", 1L), null))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void wrongAuthHeader_throwsSecurityException() {
        assertThatThrownBy(() -> svc.handleWebhook(
                payload("INITIAL_PURCHASE", "fb-uid", "APP_STORE", 1L), "Bearer wrong"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void webhookAuthNotConfigured_throwsIllegalState() {
        ReflectionTestUtils.setField(svc, "webhookAuth", "");
        assertThatThrownBy(() -> svc.handleWebhook(
                payload("INITIAL_PURCHASE", "fb-uid", "APP_STORE", 1L), "anything"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownAppUserId_isIgnored_doesNotSave() throws Exception {
        when(userRepository.findByFirebaseUid("fb-unknown")).thenReturn(Optional.empty());
        var result = svc.handleWebhook(
                payload("INITIAL_PURCHASE", "fb-unknown", "APP_STORE", 1L),
                "Bearer " + AUTH);
        assertThat(result).containsEntry("status", "ignored")
                .containsEntry("reason", "unknown_user");
        verify(userRepository, never()).save(any());
    }

    @Test
    void cancellation_logsOnly_doesNotShortenExpiry() throws Exception {
        // No findByFirebaseUid call should be needed for cancellation handling.
        var result = svc.handleWebhook(
                payload("CANCELLATION", "fb-uid", "APP_STORE", null),
                "Bearer " + AUTH);
        assertThat(result).containsEntry("status", "cancelled_logged");
        verify(userRepository, never()).save(any());
    }

    @Test
    void expiration_logsOnly_doesNotShortenExpiry() throws Exception {
        var result = svc.handleWebhook(
                payload("EXPIRATION", "fb-uid", "APP_STORE", null),
                "Bearer " + AUTH);
        assertThat(result).containsEntry("status", "cancelled_logged");
        verify(userRepository, never()).save(any());
    }

    @Test
    void billingIssue_logsOnly() throws Exception {
        var result = svc.handleWebhook(
                payload("BILLING_ISSUE", "fb-uid", "APP_STORE", null),
                "Bearer " + AUTH);
        assertThat(result).containsEntry("status", "logged");
    }

    @Test
    void unknownEventType_isIgnored() throws Exception {
        var result = svc.handleWebhook(
                payload("WEIRD_EVENT", "fb-uid", "APP_STORE", 1L),
                "Bearer " + AUTH);
        assertThat(result).containsEntry("status", "ignored");
        assertThat(result.get("reason").toString()).startsWith("unhandled:");
    }

    @Test
    void missingFields_isIgnored() throws Exception {
        var result = svc.handleWebhook(
                payload("INITIAL_PURCHASE", "fb-uid", "APP_STORE", null),
                "Bearer " + AUTH);
        assertThat(result).containsEntry("status", "ignored")
                .containsEntry("reason", "missing_fields");
        verify(userRepository, never()).save(any());
    }

    @Test
    void mapStore_recognizesAppleAndGoogle_ignoresUnknown() {
        assertThat(RevenueCatService.mapStore("APP_STORE")).isEqualTo("apple");
        assertThat(RevenueCatService.mapStore("MAC_APP_STORE")).isEqualTo("apple");
        assertThat(RevenueCatService.mapStore("PLAY_STORE")).isEqualTo("google");
        assertThat(RevenueCatService.mapStore("AMAZON")).isNull();
        assertThat(RevenueCatService.mapStore(null)).isNull();
    }
}
