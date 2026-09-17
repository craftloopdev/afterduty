package com.afterduty.controller;

import com.afterduty.config.SecurityConfig;
import com.afterduty.exception.ApiException;
import com.afterduty.model.EmailVerificationCode;
import com.afterduty.model.User;
import com.afterduty.repository.UserRepository;
import com.afterduty.service.AuthAuditService;
import com.afterduty.service.EmailCodeService;
import com.afterduty.service.FirebaseCustomTokenService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EmailCodeAuthController} — the three endpoints'
 * wiring/contract (passwordless-otp-auth-spec §2, §3.5, §11). Services are mocked;
 * this asserts the controller's normalization, IP derivation, purpose scoping,
 * anti-enumeration request shape, and the verify/attach JSON contracts.
 */
@Tag("regression")
class EmailCodeAuthControllerTest {

    private final EmailCodeService emailCodeService = mock(EmailCodeService.class);
    private final FirebaseCustomTokenService customTokenService = mock(FirebaseCustomTokenService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuthAuditService auditService = mock(AuthAuditService.class);
    private final com.afterduty.service.StripeService stripeService =
            mock(com.afterduty.service.StripeService.class);

    private final EmailCodeAuthController controller =
            new EmailCodeAuthController(emailCodeService, customTokenService, userRepository, auditService,
                    stripeService);

    // ---- email normalization / shape (§2.1) ----------------------------------

    @Test
    void normalizeEmailLowercasesAndTrims() {
        assertThat(EmailCodeAuthController.normalizeEmail("  VET@Example.COM ")).isEqualTo("vet@example.com");
    }

    @Test
    void normalizeEmailRejectsBadShapes() {
        for (String bad : new String[]{"", "  ", "noat", "@nodomain.com", "no@dotdomain", "a@b"}) {
            assertThatThrownBy(() -> EmailCodeAuthController.normalizeEmail(bad))
                    .isInstanceOf(ApiException.class)
                    .satisfies(t -> {
                        ApiException ae = (ApiException) t;
                        assertThat(ae.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(ae.getMessage()).isEqualTo("Enter a valid email address");
                    });
        }
    }

    // ---- client IP derivation (§3.5) -----------------------------------------

    @Test
    void clientIpTrustsSecondToLastXffEntryBehindLb() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        // <attacker-supplied>, <real client>, <GFE>
        req.addHeader("X-Forwarded-For", "1.1.1.1, 203.0.113.7, 35.191.0.5");
        assertThat(EmailCodeAuthController.clientIp(req)).isEqualTo("203.0.113.7");
    }

    @Test
    void clientIpUsesSingleEntryWhenNoLb() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "203.0.113.7");
        assertThat(EmailCodeAuthController.clientIp(req)).isEqualTo("203.0.113.7");
    }

    @Test
    void clientIpFallsBackToSocketPeerWhenNoXff() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("198.51.100.9");
        assertThat(EmailCodeAuthController.clientIp(req)).isEqualTo("198.51.100.9");
    }

    @Test
    void clientIpNeverTrustsLeftmostAttackerEntry() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "evil.spoof, 203.0.113.7, 35.191.0.5");
        assertThat(EmailCodeAuthController.clientIp(req)).isNotEqualTo("evil.spoof");
    }

    // ---- request (§2.1) ------------------------------------------------------

    @Test
    void requestAlwaysReturnsOkTrueForValidEmail() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        Map<String, Object> out = controller.request(Map.of("email", "vet@example.com"), req);
        assertThat(out).isEqualTo(Map.of("ok", true));
        verify(emailCodeService).mintAndSend(eq("vet@example.com"), any(), any(),
                eq(EmailVerificationCode.PURPOSE_SIGNIN), eq(false));
    }

    @Test
    void requestPurposeAttachIgnoredWhenUnauthenticated() {
        MockHttpServletRequest req = new MockHttpServletRequest();   // no USER_ATTRIBUTE
        controller.request(Map.of("email", "vet@example.com", "purpose", "attach"), req);
        // Fail-safe: an unauth attach request is treated as SIGNIN.
        verify(emailCodeService).mintAndSend(any(), any(), any(),
                eq(EmailVerificationCode.PURPOSE_SIGNIN), eq(false));
        verify(emailCodeService, never()).mintAndSend(any(), any(), any(),
                eq(EmailVerificationCode.PURPOSE_ATTACH), anyBoolean());
    }

    @Test
    void requestPurposeAttachHonoredWhenAuthenticated() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(SecurityConfig.USER_ATTRIBUTE, new User());
        controller.request(Map.of("email", "vet@example.com", "purpose", "attach"), req);
        verify(emailCodeService).mintAndSend(eq("vet@example.com"), any(), any(),
                eq(EmailVerificationCode.PURPOSE_ATTACH), eq(true));
    }

    // ---- verify (§2.2) -------------------------------------------------------

    @Test
    void verifyReturnsTokenAndFlags() {
        when(customTokenService.mintForVerifiedEmail("vet@example.com"))
                .thenReturn(new FirebaseCustomTokenService.MintResult("tok-abc", true, false));

        Map<String, Object> out = controller.verify(
                Map.of("email", "vet@example.com", "code", "123456"), new MockHttpServletRequest());

        assertThat(out.get("custom_token")).isEqualTo("tok-abc");
        assertThat(out.get("isNewUser")).isEqualTo(true);
        assertThat(out.get("hasPhone")).isEqualTo(false);
        // verify+consume runs BEFORE the token mint (consume-before-act).
        verify(emailCodeService).verifyAndConsume("vet@example.com", "123456",
                EmailVerificationCode.PURPOSE_SIGNIN);
    }

    @Test
    void verifyMissingEmailIs400() {
        assertThatThrownBy(() -> controller.verify(Map.of("code", "123456"), new MockHttpServletRequest()))
                .isInstanceOf(ApiException.class)
                .satisfies(t -> assertThat(((ApiException) t).getMessage()).isEqualTo("Enter your email"));
    }

    @Test
    void verifyMissingCodeIs400() {
        assertThatThrownBy(() -> controller.verify(Map.of("email", "vet@example.com"), new MockHttpServletRequest()))
                .isInstanceOf(ApiException.class)
                .satisfies(t -> assertThat(((ApiException) t).getMessage()).isEqualTo("Enter the code"));
    }

    @Test
    void verifyNeverMintsTokenIfConsumeThrows() {
        org.mockito.Mockito.doThrow(ApiException.badRequest("Incorrect or expired code"))
                .when(emailCodeService).verifyAndConsume(any(), any(), any());

        assertThatThrownBy(() -> controller.verify(
                Map.of("email", "vet@example.com", "code", "000000"), new MockHttpServletRequest()))
                .isInstanceOf(ApiException.class);
        verify(customTokenService, never()).mintForVerifiedEmail(any());
    }

    // ---- attach (§2.3) -------------------------------------------------------

    @Test
    void attachVerifiesWithAttachPurposeWritesAppUserAndReturnsContract() {
        User user = new User();
        user.setFirebaseUid("uid-current");
        user.setEmail("old@example.com");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(SecurityConfig.USER_ATTRIBUTE, user);

        when(customTokenService.attachEmailToCurrentUser("uid-current", "add@example.com"))
                .thenReturn(new FirebaseCustomTokenService.AttachResult("add@example.com", true));

        Map<String, Object> out = controller.attach(
                Map.of("email", "add@example.com", "code", "654321"), req);

        assertThat(out.get("status")).isEqualTo("verified");
        assertThat(out.get("email")).isEqualTo("add@example.com");
        assertThat(out.get("emailVerified")).isEqualTo(true);
        verify(emailCodeService).verifyAndConsume("add@example.com", "654321",
                EmailVerificationCode.PURPOSE_ATTACH);
        // Mirrored to the app User row.
        assertThat(user.getEmail()).isEqualTo("add@example.com");
        verify(userRepository).save(user);
        // A phone-OTP subscriber's email-less Stripe customer becomes reachable.
        verify(stripeService).syncCustomerEmail(user);
    }

    @Test
    void attachMissingCodeIs400() {
        User user = new User();
        user.setFirebaseUid("uid-current");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(SecurityConfig.USER_ATTRIBUTE, user);

        assertThatThrownBy(() -> controller.attach(Map.of("email", "add@example.com"), req))
                .isInstanceOf(ApiException.class)
                .satisfies(t -> assertThat(((ApiException) t).getMessage())
                        .isEqualTo("Enter the verification code"));
        verify(emailCodeService, never()).verifyAndConsume(any(), any(), any());
    }
}
