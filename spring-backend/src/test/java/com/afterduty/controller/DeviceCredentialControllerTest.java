package com.afterduty.controller;

import com.afterduty.config.SecurityConfig;
import com.afterduty.exception.ApiException;
import com.afterduty.exception.StepUpRequiredException;
import com.afterduty.model.DeviceCredential;
import com.afterduty.model.User;
import com.afterduty.service.AuthAuditService;
import com.afterduty.service.DeviceCredentialService;
import com.afterduty.service.StepUpService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract tests for {@link DeviceCredentialController} (auth program P1.4, the B2
 * device-bound-token contract). The crypto/lifecycle is covered by
 * {@code DeviceCredentialServiceTest}; here the {@link DeviceCredentialService} is
 * mocked and we assert the controller WIRING: enroll returns the secret once +
 * audits DEVICE_ENROLLED; exchange mints the SAME custom-token shape the
 * email/passkey path returns + audits DEVICE_LOGIN success/failure with
 * channel=biometric; the DELETE is step-up guarded (403 without X-Step-Up, 204
 * with a valid one) + audits DEVICE_REVOKED; list never leaks the secret hash.
 */
@Tag("regression")
class DeviceCredentialControllerTest {

    private final DeviceCredentialService deviceService = mock(DeviceCredentialService.class);
    private final StepUpService stepUpService = mock(StepUpService.class);
    private final AuthAuditService audit = mock(AuthAuditService.class);

    private final DeviceCredentialController controller =
            new DeviceCredentialController(deviceService, stepUpService, audit);

    private static User vet() {
        User u = new User();
        u.setId(9L);
        u.setEmail("vet@example.com");
        u.setFirebaseUid("uid-9");
        return u;
    }

    private static MockHttpServletRequest authed(User user) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(SecurityConfig.USER_ATTRIBUTE, user);
        return req;
    }

    // ---- ENROLL --------------------------------------------------------------

    @Test
    void enrollReturnsSecretOnceAndAudits() {
        User user = vet();
        when(deviceService.enroll(eq(user), eq("iPhone 15"), eq("ios")))
                .thenReturn(new DeviceCredentialService.EnrollResult("SECRET_ONCE_b64url", 42L));

        Map<String, Object> out = controller.enroll(
                Map.of("deviceName", "iPhone 15", "platform", "ios"), authed(user));

        assertThat(out.get("deviceSecret")).isEqualTo("SECRET_ONCE_b64url");
        assertThat(out.get("deviceCredentialId")).isEqualTo(42L);
        // DEVICE_ENROLLED audited on the biometric channel.
        verify(audit).record(eq("DEVICE_ENROLLED"), eq("biometric"), eq("SUCCESS"),
                eq(9L), any(), any());
    }

    // ---- EXCHANGE ------------------------------------------------------------

    @Test
    void exchangeMintsCustomTokenSameShapeAsEmailPath() {
        when(deviceService.exchange(eq(42L), eq("secret-xyz")))
                .thenReturn(Optional.of(new DeviceCredentialService.ExchangeResult("custom-token-abc", 9L)));

        Map<String, Object> out = controller.exchange(
                Map.of("deviceCredentialId", 42, "deviceSecret", "secret-xyz"),
                new MockHttpServletRequest());

        // SAME key ("custom_token") the email-code / passkey verify returns.
        assertThat(out.get("custom_token")).isEqualTo("custom-token-abc");
        // No token/secret leaks into the payload.
        assertThat(out).doesNotContainKey("deviceSecret");
        verify(audit).record(eq("DEVICE_LOGIN"), eq("biometric"), eq("SUCCESS"), eq(9L), any(), any());
    }

    @Test
    void exchangeAcceptsNumericStringId() {
        when(deviceService.exchange(eq(42L), eq("secret-xyz")))
                .thenReturn(Optional.of(new DeviceCredentialService.ExchangeResult("t", 9L)));

        Map<String, Object> out = controller.exchange(
                Map.of("deviceCredentialId", "42", "deviceSecret", "secret-xyz"),
                new MockHttpServletRequest());

        assertThat(out.get("custom_token")).isEqualTo("t");
    }

    @Test
    void exchangeFailureIs400AndAuditsFailure() {
        // Wrong secret / revoked / unknown id all collapse to empty (anti-enumeration).
        when(deviceService.exchange(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.exchange(
                Map.of("deviceCredentialId", 42, "deviceSecret", "wrong"),
                new MockHttpServletRequest()))
                .isInstanceOf(ApiException.class);

        verify(audit).record(eq("DEVICE_LOGIN"), eq("biometric"), eq("FAILURE"), any(), any(), any());
    }

    // ---- MANAGE: list --------------------------------------------------------

    @Test
    void listCredentialsNeverLeaksSecretHash() {
        User user = vet();
        when(deviceService.listActive(9L)).thenReturn(List.of(
                new DeviceCredentialService.CredentialSummary(
                        7L, "iPhone 15", "ios", Instant.now(), Instant.now())));

        Map<String, Object> out = controller.listCredentials(authed(user));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> creds = (List<Map<String, Object>>) out.get("credentials");
        assertThat(creds).hasSize(1);
        Map<String, Object> m = creds.get(0);
        assertThat(m.get("id")).isEqualTo(7L);
        assertThat(m.get("deviceName")).isEqualTo("iPhone 15");
        assertThat(m.get("platform")).isEqualTo("ios");
        // No secret hash exposed.
        assertThat(m).doesNotContainKeys("deviceSecretHash", "deviceSecret");
    }

    // ---- MANAGE: revoke (STEP-UP GUARDED) ------------------------------------

    @Test
    void revokeWithoutStepUpIs403AndDoesNotRevoke() {
        User user = vet();
        doThrow(new StepUpRequiredException()).when(stepUpService).requireFresh(eq(user), any());

        assertThatThrownBy(() -> controller.revokeCredential(7L, authed(user)))
                .isInstanceOf(StepUpRequiredException.class)
                .satisfies(t -> assertThat(((StepUpRequiredException) t).getAcceptedFactors())
                        .containsExactly("otp"));

        // No revoke happens and nothing is audited.
        verify(deviceService, never()).revoke(any(), any());
        verify(audit, never()).record(eq("DEVICE_REVOKED"), any(), any(), any(), any(), any());
    }

    @Test
    void revokeWithValidStepUpRevokesAndAudits() {
        User user = vet();
        // requireFresh returns (a valid X-Step-Up was present + consumed).
        when(deviceService.revoke(7L, 9L)).thenReturn(Optional.of(new DeviceCredential()));

        ResponseEntity<Void> res = controller.revokeCredential(7L, authed(user));

        assertThat(res.getStatusCode().value()).isEqualTo(204);
        verify(deviceService).revoke(7L, 9L);
        verify(audit).record(eq("DEVICE_REVOKED"), eq("biometric"), eq("SUCCESS"), eq(9L), any(), any());
    }

    @Test
    void revokeUnknownIdIs400() {
        User user = vet();
        when(deviceService.revoke(7L, 9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.revokeCredential(7L, authed(user)))
                .isInstanceOf(ApiException.class);
        verify(audit, never()).record(eq("DEVICE_REVOKED"), any(), any(), any(), any(), any());
    }
}
