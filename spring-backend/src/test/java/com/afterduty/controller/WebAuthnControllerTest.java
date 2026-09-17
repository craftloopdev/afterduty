package com.afterduty.controller;

import com.afterduty.config.SecurityConfig;
import com.afterduty.exception.ApiException;
import com.afterduty.exception.StepUpRequiredException;
import com.afterduty.model.User;
import com.afterduty.model.WebAuthnCredential;
import com.afterduty.repository.UserRepository;
import com.afterduty.repository.WebAuthnCredentialRepository;
import com.afterduty.service.AuthAuditService;
import com.afterduty.service.StepUpService;
import com.afterduty.service.webauthn.WebAuthnService;
import com.afterduty.service.webauthn.WebAuthnSessionMinter;
import com.afterduty.service.webauthn.WebAuthnUserResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
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
 * Contract tests for {@link WebAuthnController} (auth program P1.3). The
 * ceremony/crypto is covered end-to-end by {@code WebAuthnServiceTest}; here the
 * {@link WebAuthnService} is mocked and we assert the controller's WIRING:
 * register/verify audits + shape; assert/verify mints the SAME custom-token shape
 * the email path returns; the DELETE is step-up guarded (403 without X-Step-Up,
 * 204 with a valid one); anti-enumeration passes the resolver's Optional through;
 * rename validates; list never leaks key material.
 */
@Tag("regression")
class WebAuthnControllerTest {

    private final WebAuthnService webAuthnService = mock(WebAuthnService.class);
    private final WebAuthnUserResolver userResolver = mock(WebAuthnUserResolver.class);
    private final WebAuthnSessionMinter sessionMinter = mock(WebAuthnSessionMinter.class);
    private final WebAuthnCredentialRepository credentialRepo = mock(WebAuthnCredentialRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final StepUpService stepUpService = mock(StepUpService.class);
    private final AuthAuditService audit = mock(AuthAuditService.class);

    private final WebAuthnController controller = new WebAuthnController(
            webAuthnService, userResolver, sessionMinter, credentialRepo,
            userRepository, stepUpService, audit);

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

    /** A credential owned by user 9 with its generated {@code id} forced (no setter). */
    private static WebAuthnCredential credWithId(long id) {
        WebAuthnCredential c = new WebAuthnCredential();
        c.setUserId(9L);
        c.setCredentialId("cred-abc");
        try {
            java.lang.reflect.Field f = WebAuthnCredential.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(c, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return c;
    }

    // ---- REGISTER ------------------------------------------------------------

    @Test
    void registerOptionsReturnsRawJson() {
        when(webAuthnService.startRegistration(any())).thenReturn("{\"publicKey\":{}}");
        ResponseEntity<String> res = controller.registerOptions(authed(vet()));
        assertThat(res.getBody()).isEqualTo("{\"publicKey\":{}}");
        assertThat(res.getHeaders().getContentType().toString()).contains("application/json");
    }

    @Test
    void registerVerifyPersistsAndAuditsAndReturnsContract() {
        User user = vet();
        when(webAuthnService.finishRegistration(eq(user), any(), eq("My Key")))
                .thenReturn(new WebAuthnService.RegisterResult("cred-abc", "My Key"));

        Map<String, Object> out = controller.registerVerify(
                Map.of("credential", Map.of("id", "cred-abc"), "nickname", "My Key"), authed(user));

        assertThat(out.get("credentialId")).isEqualTo("cred-abc");
        assertThat(out.get("nickname")).isEqualTo("My Key");
        // PASSKEY_REGISTERED audited.
        verify(audit).record(eq("PASSKEY_REGISTERED"), eq("passkey"), eq("SUCCESS"),
                eq(9L), any(), any());
    }

    // ---- AUTHENTICATE --------------------------------------------------------

    @Test
    void assertOptionsPassesResolvedUserThrough_knownUser() {
        User user = vet();
        when(userResolver.resolve("vet@example.com")).thenReturn(Optional.of(user));
        when(webAuthnService.startAssertion(Optional.of(user))).thenReturn("{\"publicKey\":{\"challenge\":\"x\"}}");

        ResponseEntity<String> res = controller.assertOptions(
                Map.of("identifier", "vet@example.com"), new MockHttpServletRequest());

        assertThat(res.getBody()).contains("challenge");
        verify(webAuthnService).startAssertion(Optional.of(user));
    }

    @Test
    void assertOptionsUnknownIdentifierStillReturnsOptions_antiEnumeration() {
        // The resolver returns empty for an unknown identifier; the controller must
        // STILL call startAssertion (with empty) so a decoy options object is
        // returned — never a 404 that would reveal the account doesn't exist.
        when(userResolver.resolve("ghost@example.com")).thenReturn(Optional.empty());
        when(webAuthnService.startAssertion(Optional.empty()))
                .thenReturn("{\"publicKey\":{\"challenge\":\"decoy\"}}");

        ResponseEntity<String> res = controller.assertOptions(
                Map.of("identifier", "ghost@example.com"), new MockHttpServletRequest());

        assertThat(res.getBody()).contains("decoy");
        verify(webAuthnService).startAssertion(Optional.empty());
    }

    @Test
    void assertVerifyMintsCustomTokenSameShapeAsEmailPath() {
        User user = vet();
        when(webAuthnService.finishAssertion(any()))
                .thenReturn(Optional.of(new WebAuthnService.AssertResult(9L, "cred-abc")));
        when(userRepository.findById(9L)).thenReturn(Optional.of(user));
        when(sessionMinter.mintCustomToken("uid-9")).thenReturn("custom-token-xyz");

        Map<String, Object> out = controller.assertVerify(
                Map.of("credential", Map.of("id", "cred-abc")), new MockHttpServletRequest());

        // SAME key ("custom_token") the email-code verify returns, so the web driver
        // reuses signInWithCustomTokenAndEstablish unchanged.
        assertThat(out.get("custom_token")).isEqualTo("custom-token-xyz");
        assertThat(out.get("credentialId")).isEqualTo("cred-abc");
        // Minted for the credential's OWNING uid only.
        verify(sessionMinter).mintCustomToken("uid-9");
        verify(audit).record(eq("PASSKEY_LOGIN"), eq("passkey"), eq("SUCCESS"), eq(9L), any(), any());
    }

    @Test
    void assertVerifyFailureIs400AndMintsNoToken() {
        when(webAuthnService.finishAssertion(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.assertVerify(
                Map.of("credential", Map.of("id", "x")), new MockHttpServletRequest()))
                .isInstanceOf(ApiException.class);

        verify(sessionMinter, never()).mintCustomToken(any());
        // A failed PASSKEY_LOGIN is audited.
        verify(audit).record(eq("PASSKEY_LOGIN"), eq("passkey"), eq("FAILURE"), any(), any(), any());
    }

    // ---- MANAGE: list --------------------------------------------------------

    @Test
    void listCredentialsNeverLeaksKeyMaterial() {
        User user = vet();
        WebAuthnCredential c = new WebAuthnCredential();
        c.setUserId(9L);
        c.setCredentialId("cred-abc");
        c.setUserHandle("handle");
        c.setPublicKeyCose("SECRET_COSE_KEY");
        c.setNickname("Laptop");
        c.setTransports("internal");
        c.setCreatedAt(Instant.now());
        when(credentialRepo.findByUserId(9L)).thenReturn(List.of(c));

        Map<String, Object> out = controller.listCredentials(authed(user));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> creds = (List<Map<String, Object>>) out.get("credentials");
        assertThat(creds).hasSize(1);
        Map<String, Object> m = creds.get(0);
        assertThat(m.get("nickname")).isEqualTo("Laptop");
        assertThat(m.get("deviceHint")).isEqualTo("This device");
        // No public key, no credential id, no user handle exposed.
        assertThat(m).doesNotContainKeys("publicKeyCose", "credentialId", "userHandle");
        assertThat(m.values()).doesNotContain("SECRET_COSE_KEY", "cred-abc", "handle");
    }

    // ---- MANAGE: rename ------------------------------------------------------

    @Test
    void renameUpdatesNickname() {
        User user = vet();
        WebAuthnCredential c = credWithId(5L);
        when(credentialRepo.findByIdAndUserId(5L, 9L)).thenReturn(Optional.of(c));

        Map<String, Object> out = controller.renameCredential(5L, Map.of("nickname", "  Work key  "), authed(user));
        assertThat(out.get("nickname")).isEqualTo("Work key");
        verify(credentialRepo).save(c);
    }

    @Test
    void renameBlankIs400() {
        User user = vet();
        WebAuthnCredential c = new WebAuthnCredential();
        c.setUserId(9L);
        when(credentialRepo.findByIdAndUserId(5L, 9L)).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> controller.renameCredential(5L, Map.of("nickname", "   "), authed(user)))
                .isInstanceOf(ApiException.class);
    }

    // ---- MANAGE: delete (STEP-UP GUARDED) ------------------------------------

    @Test
    void deleteWithoutStepUpIs403() {
        User user = vet();
        // The guard throws when no valid X-Step-Up is present.
        doThrow(new StepUpRequiredException()).when(stepUpService).requireFresh(eq(user), any());

        assertThatThrownBy(() -> controller.deleteCredential(5L, authed(user)))
                .isInstanceOf(StepUpRequiredException.class)
                .satisfies(t -> assertThat(((StepUpRequiredException) t).getAcceptedFactors())
                        .containsExactly("otp"));

        // The credential is NOT deleted and no revoke is audited.
        verify(credentialRepo, never()).delete(any());
        verify(audit, never()).record(eq("PASSKEY_REVOKED"), any(), any(), any(), any(), any());
    }

    @Test
    void deleteWithValidStepUpDeletesAndAudits() {
        User user = vet();
        // Guard passes (a valid X-Step-Up was present + consumed) — requireFresh returns.
        WebAuthnCredential c = new WebAuthnCredential();
        c.setUserId(9L);
        c.setCredentialId("cred-abc");
        when(credentialRepo.findByIdAndUserId(5L, 9L)).thenReturn(Optional.of(c));

        ResponseEntity<Void> res = controller.deleteCredential(5L, authed(user));

        assertThat(res.getStatusCode().value()).isEqualTo(204);
        verify(credentialRepo).delete(c);
        verify(audit).record(eq("PASSKEY_REVOKED"), eq("passkey"), eq("SUCCESS"), eq(9L), any(), any());
    }
}
