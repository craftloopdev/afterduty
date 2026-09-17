package com.afterduty.controller;

import com.afterduty.config.SecurityConfig;
import com.afterduty.exception.ApiException;
import com.afterduty.model.EmailVerificationCode;
import com.afterduty.model.StepUpToken;
import com.afterduty.model.User;
import com.afterduty.service.AuthAuditService;
import com.afterduty.service.EmailCodeService;
import com.afterduty.service.FirebaseAuthService;
import com.afterduty.service.StepUpService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

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
 * Contract tests for {@link StepUpController} (auth program P1.2 — the pinned
 * {@code POST /api/auth/step-up/verify} contract). Services are mocked; this
 * asserts factor/channel routing, that the email path verifies against the
 * caller's OWN email in the STEPUP purpose lane, that a bad code / bad token is a
 * 400 (no token minted), that a 429 propagates, and the success body shape.
 */
@Tag("regression")
class StepUpControllerTest {

    private final EmailCodeService emailCodeService = mock(EmailCodeService.class);
    private final FirebaseAuthService firebaseAuthService = mock(FirebaseAuthService.class);
    private final StepUpService stepUpService = mock(StepUpService.class);
    private final AuthAuditService audit = mock(AuthAuditService.class);

    private final StepUpController controller =
            new StepUpController(emailCodeService, firebaseAuthService, stepUpService, audit);

    private static MockHttpServletRequest authed(User user) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(SecurityConfig.USER_ATTRIBUTE, user);
        return req;
    }

    private static User vet() {
        User u = new User();
        u.setId(9L);
        u.setEmail("vet@example.com");
        u.setFirebaseUid("uid-9");
        return u;
    }

    // ---- email OTP happy path ------------------------------------------------

    @Test
    void emailOtpHappyPathMintsAndReturnsContract() {
        User user = vet();
        when(stepUpService.mint(9L, StepUpToken.FACTOR_OTP))
                .thenReturn(new StepUpService.MintResult("opaque-token", 300));

        Map<String, Object> out = controller.verify(
                Map.of("factor", "otp", "channel", "email", "code", "123456"), authed(user));

        assertThat(out.get("stepUpToken")).isEqualTo("opaque-token");
        assertThat(out.get("expiresInSec")).isEqualTo(300);
        // Verified against the caller's OWN email in the STEPUP purpose lane.
        verify(emailCodeService).verifyAndConsume("vet@example.com", "123456",
                EmailVerificationCode.PURPOSE_STEPUP);
        verify(stepUpService).mint(9L, StepUpToken.FACTOR_OTP);
    }

    @Test
    void emailBadCodeIs400AndMintsNoToken() {
        User user = vet();
        org.mockito.Mockito.doThrow(ApiException.badRequest("Incorrect or expired code"))
                .when(emailCodeService).verifyAndConsume(any(), any(), any());

        assertThatThrownBy(() -> controller.verify(
                Map.of("factor", "otp", "channel", "email", "code", "000000"), authed(user)))
                .isInstanceOf(ApiException.class)
                .satisfies(t -> assertThat(((ApiException) t).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        // No step-up token is minted when the code is wrong.
        verify(stepUpService, never()).mint(any(), any());
    }

    @Test
    void emailRateLimit429Propagates() {
        User user = vet();
        org.mockito.Mockito.doThrow(ApiException.tooManyRequests("Too many codes requested. Try again later."))
                .when(emailCodeService).verifyAndConsume(any(), any(), any());

        assertThatThrownBy(() -> controller.verify(
                Map.of("factor", "otp", "channel", "email", "code", "123456"), authed(user)))
                .isInstanceOf(ApiException.class)
                .satisfies(t -> assertThat(((ApiException) t).getStatus())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(stepUpService, never()).mint(any(), any());
    }

    @Test
    void emailMissingCodeIs400() {
        assertThatThrownBy(() -> controller.verify(
                Map.of("factor", "otp", "channel", "email"), authed(vet())))
                .isInstanceOf(ApiException.class)
                .satisfies(t -> assertThat(((ApiException) t).getMessage())
                        .contains("Enter the verification code"));
        verify(emailCodeService, never()).verifyAndConsume(any(), any(), any());
    }

    // ---- phone path ----------------------------------------------------------

    @Test
    void phoneHappyPathVerifiesFreshIdTokenUidAgainstCurrentUser() {
        User user = vet();
        // The fresh-phone check (auth_time within the step-up window) is what the
        // controller MUST call — a bare uid check would be satisfied by the caller's
        // own stale session token and re-prove nothing.
        when(firebaseAuthService.verifyFreshPhoneUid("fb-id-token", StepUpService.STEP_UP_TTL_SEC))
                .thenReturn("uid-9");
        when(stepUpService.mint(9L, StepUpToken.FACTOR_OTP))
                .thenReturn(new StepUpService.MintResult("opaque", 300));

        Map<String, Object> out = controller.verify(
                Map.of("factor", "otp", "channel", "phone", "idToken", "fb-id-token"), authed(user));

        assertThat(out.get("stepUpToken")).isEqualTo("opaque");
        verify(firebaseAuthService).verifyFreshPhoneUid("fb-id-token", StepUpService.STEP_UP_TTL_SEC);
        // The non-fresh uid check must NOT be the step-up path.
        verify(firebaseAuthService, never()).verifyIdTokenUid(any());
        verify(stepUpService).mint(9L, StepUpToken.FACTOR_OTP);
    }

    @Test
    void phoneUidMismatchIs400AndMintsNoToken() {
        User user = vet();
        // Token verifies fresh but belongs to a DIFFERENT uid — possession not proven.
        when(firebaseAuthService.verifyFreshPhoneUid("fb-id-token", StepUpService.STEP_UP_TTL_SEC))
                .thenReturn("uid-someone-else");

        assertThatThrownBy(() -> controller.verify(
                Map.of("factor", "otp", "channel", "phone", "idToken", "fb-id-token"), authed(user)))
                .isInstanceOf(ApiException.class)
                .satisfies(t -> assertThat(((ApiException) t).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(stepUpService, never()).mint(any(), any());
    }

    @Test
    void phoneStaleOrInvalidTokenIs400() {
        User user = vet();
        // verifyFreshPhoneUid returns null for BOTH an invalid/expired token AND a
        // valid-but-stale one (auth_time outside the step-up window — i.e. a replayed
        // session token). Either way: 400, no token minted.
        when(firebaseAuthService.verifyFreshPhoneUid(any(), eq((long) StepUpService.STEP_UP_TTL_SEC)))
                .thenReturn(null);
        assertThatThrownBy(() -> controller.verify(
                Map.of("factor", "otp", "channel", "phone", "idToken", "stale-or-bad"), authed(user)))
                .isInstanceOf(ApiException.class);
        verify(stepUpService, never()).mint(any(), any());
    }

    // ---- factor / channel guards --------------------------------------------

    @Test
    void unsupportedFactorIs400() {
        assertThatThrownBy(() -> controller.verify(
                Map.of("factor", "passkey", "channel", "email", "code", "123456"), authed(vet())))
                .isInstanceOf(ApiException.class)
                .satisfies(t -> assertThat(((ApiException) t).getMessage()).contains("factor"));
        verify(emailCodeService, never()).verifyAndConsume(any(), any(), any());
    }

    @Test
    void unsupportedChannelIs400() {
        assertThatThrownBy(() -> controller.verify(
                Map.of("factor", "otp", "channel", "carrier-pigeon", "code", "123456"), authed(vet())))
                .isInstanceOf(ApiException.class)
                .satisfies(t -> assertThat(((ApiException) t).getMessage()).contains("channel"));
    }
}
