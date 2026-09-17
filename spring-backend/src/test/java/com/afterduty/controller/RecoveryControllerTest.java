package com.afterduty.controller;

import com.afterduty.exception.ApiException;
import com.afterduty.exception.RecoveryNeedsSupportException;
import com.afterduty.service.RecoveryService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Controller-wiring tests for {@link RecoveryController} (auth program P1.5 — the
 * pinned C contract). The crypto/lifecycle is covered by {@code RecoveryServiceTest};
 * here the {@link RecoveryService} is mocked and we assert the HTTP wiring: start
 * is always {@code {ok:true}}; verify returns the SAME {@code custom_token} shape
 * the sign-in path returns + the revoked-factor list; the single-channel 409 and
 * the generic 400 propagate unchanged; verify validates its inputs.
 */
@Tag("regression")
class RecoveryControllerTest {

    private final RecoveryService recoveryService = mock(RecoveryService.class);
    private final RecoveryController controller = new RecoveryController(recoveryService);

    private static MockHttpServletRequest req() {
        return new MockHttpServletRequest();
    }

    // ---- start ---------------------------------------------------------------

    @Test
    void startAlwaysReturnsOkAndDelegates() {
        Map<String, Object> out = controller.start(Map.of("identifier", "vet@example.com"), req());
        assertThat(out.get("ok")).isEqualTo(true);
        verify(recoveryService).start(eq("vet@example.com"), any(), any());
    }

    @Test
    void startWithMissingIdentifierStillReturnsOk() {
        // Anti-enumeration: even an empty identifier is a flat 200 (the service
        // resolves nothing and stays silent).
        Map<String, Object> out = controller.start(Map.of(), req());
        assertThat(out.get("ok")).isEqualTo(true);
        verify(recoveryService).start(eq(""), any(), any());
    }

    // ---- verify --------------------------------------------------------------

    @Test
    void verifyReturnsCustomTokenSameShapeAsSignIn() {
        when(recoveryService.verify(eq("vet@example.com"), eq("246810"), eq("phone-tok"), any()))
                .thenReturn(new RecoveryService.RecoverResult("custom-token-abc", 9L));

        Map<String, Object> out = controller.verify(
                Map.of("email", "VET@example.com", "emailCode", "246810", "phoneIdToken", "phone-tok"),
                req());

        assertThat(out.get("custom_token")).isEqualTo("custom-token-abc");
        // The UI is told what was cleared so the "set up Face ID again" copy is honest.
        assertThat(out.get("revokedFactors")).isEqualTo(List.of("passkey", "biometric"));
        // No token/secret smuggled into the payload.
        assertThat(out).doesNotContainKeys("deviceSecret", "phoneIdToken", "emailCode");
    }

    @Test
    void verifyNormalizesEmailToLowercaseBeforeDelegating() {
        when(recoveryService.verify(eq("vet@example.com"), any(), any(), any()))
                .thenReturn(new RecoveryService.RecoverResult("t", 1L));

        controller.verify(
                Map.of("email", "  VET@Example.com ", "emailCode", "246810", "phoneIdToken", "tok"),
                req());

        verify(recoveryService).verify(eq("vet@example.com"), eq("246810"), eq("tok"), any());
    }

    @Test
    void verifyMissingEmailIs400() {
        assertThatThrownBy(() -> controller.verify(
                Map.of("emailCode", "246810", "phoneIdToken", "tok"), req()))
                .isInstanceOf(ApiException.class);
        verify(recoveryService, never()).verify(any(), any(), any(), any());
    }

    @Test
    void verifyMissingEmailCodeIs400() {
        assertThatThrownBy(() -> controller.verify(
                Map.of("email", "vet@example.com", "phoneIdToken", "tok"), req()))
                .isInstanceOf(ApiException.class);
        verify(recoveryService, never()).verify(any(), any(), any(), any());
    }

    @Test
    void verifyMissingPhoneTokenIs400() {
        assertThatThrownBy(() -> controller.verify(
                Map.of("email", "vet@example.com", "emailCode", "246810"), req()))
                .isInstanceOf(ApiException.class);
        verify(recoveryService, never()).verify(any(), any(), any(), any());
    }

    @Test
    void verifyPropagatesSingleChannel409() {
        when(recoveryService.verify(any(), any(), any(), any()))
                .thenThrow(new RecoveryNeedsSupportException());

        assertThatThrownBy(() -> controller.verify(
                Map.of("email", "vet@example.com", "emailCode", "246810", "phoneIdToken", "tok"), req()))
                .isInstanceOf(RecoveryNeedsSupportException.class);
    }

    @Test
    void verifyPropagatesGenericFactorFailure() {
        when(recoveryService.verify(any(), any(), any(), any()))
                .thenThrow(ApiException.badRequest("Recovery failed."));

        assertThatThrownBy(() -> controller.verify(
                Map.of("email", "vet@example.com", "emailCode", "000000", "phoneIdToken", "tok"), req()))
                .isInstanceOf(ApiException.class)
                .isNotInstanceOf(RecoveryNeedsSupportException.class);
    }

    // ---- clientIp (LB-aware) --------------------------------------------------

    @Test
    void clientIpTrustsSecondToLastXffEntry() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.addHeader("X-Forwarded-For", "1.1.1.1, 2.2.2.2, 3.3.3.3");
        assertThat(RecoveryController.clientIp(r)).isEqualTo("2.2.2.2");
    }
}
