package com.afterduty.service.webauthn;

import com.afterduty.exception.ApiException;
import com.afterduty.model.User;
import com.afterduty.model.WebAuthnChallenge;
import com.afterduty.model.WebAuthnCredential;
import com.afterduty.repository.WebAuthnCredentialRepository;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResultV2;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorAssertionResponse;
import com.yubico.webauthn.data.AuthenticatorAttestationResponse;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs;
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.ResidentKeyRequirement;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The WebAuthn / passkey ceremony service (auth program P1.3). Wraps the Yubico
 * {@code RelyingPartyV2} — which does ALL crypto verification — and owns the
 * server-side challenge store, credential persistence, and sign-count bumping.
 *
 * <p>Four ceremonies (the pinned contract):
 * <ul>
 *   <li><b>register/options</b> — start enrollment for the authed user
 *       (excludeCredentials = their existing passkeys); store the challenge.</li>
 *   <li><b>register/verify</b> — verify the attestation, persist the credential.</li>
 *   <li><b>assert/options</b> — pre-session; resolve the user by identifier
 *       WITHOUT leaking existence (decoy empty-allowCredentials for unknown);
 *       store the challenge.</li>
 *   <li><b>assert/verify</b> — verify the assertion (challenge single-use + TTL,
 *       origin, rpId, signature, sign-count monotonicity, UV); bump the counter;
 *       return the resolved user id so the controller can mint the session.</li>
 * </ul>
 *
 * <p><b>What the library guarantees</b> (we do NOT re-implement any of it):
 * challenge is bound to the ceremony; exact origin + rpId hash; signature;
 * sign_count monotonic (regression ⇒ {@code AssertionFailedException}); UV flag
 * per policy; the asserted credential belongs to the asserting user handle.
 */
@Service
public class WebAuthnService {

    private static final Logger log = LoggerFactory.getLogger(WebAuthnService.class);

    /** Ceremony timeout advertised to the browser (ms). */
    private static final long CEREMONY_TIMEOUT_MS = 60_000L;

    private final WebAuthnRelyingParty relyingParty;
    private final WebAuthnCredentialRepository credentialRepo;
    private final WebAuthnChallengeStore challengeStore;

    public WebAuthnService(WebAuthnRelyingParty relyingParty,
                           WebAuthnCredentialRepository credentialRepo,
                           WebAuthnChallengeStore challengeStore) {
        this.relyingParty = relyingParty;
        this.credentialRepo = credentialRepo;
        this.challengeStore = challengeStore;
    }

    // ---- results returned to the controller ---------------------------------

    /** register/verify outcome — the persisted credential's public identity. */
    public record RegisterResult(String credentialId, String nickname) {
    }

    /** assert/verify outcome — the resolved user + the credential that signed. */
    public record AssertResult(Long userId, String credentialId) {
    }

    // ---- REGISTER: options ---------------------------------------------------

    /**
     * Start passkey enrollment for {@code user}. excludeCredentials is the user's
     * existing passkeys (so the same authenticator isn't enrolled twice). The
     * challenge (the whole creation-options object) is stored server-side, keyed
     * to the user and ceremony=register, TTL 300s.
     *
     * @return the {@code navigator.credentials.create()} JSON the browser needs
     */
    public String startRegistration(User user) {
        challengeStore.sweepExpired();

        ByteArray userHandle = WebAuthnRelyingParty.userHandle(user.getId());
        UserIdentity identity = UserIdentity.builder()
                .name(displayIdentifier(user))
                .displayName(displayName(user))
                .id(userHandle)
                .build();

        AuthenticatorSelectionCriteria selection = AuthenticatorSelectionCriteria.builder()
                .residentKey(ResidentKeyRequirement.PREFERRED)
                .userVerification(UserVerificationRequirement.PREFERRED)
                .build();

        StartRegistrationOptions opts = StartRegistrationOptions.builder()
                .user(identity)
                .authenticatorSelection(selection)
                .timeout(CEREMONY_TIMEOUT_MS)
                .build();

        PublicKeyCredentialCreationOptions creationOptions = relyingParty.rp().startRegistration(opts);

        try {
            challengeStore.store(WebAuthnChallenge.CEREMONY_REGISTER, user.getId(),
                    creationOptions.getChallenge().getBase64Url(), creationOptions.toJson());
            return creationOptions.toCredentialsCreateJson();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw ApiException.badGateway("Could not start passkey registration.");
        }
    }

    // ---- REGISTER: verify ----------------------------------------------------

    /**
     * Verify an attestation response and persist the credential for {@code user}.
     * The stored challenge is consumed (single-use) and must be a register
     * challenge bound to THIS user. Attestation verification (including that the
     * credential is new) is the library's job.
     *
     * @param credentialJson the raw {@code PublicKeyCredential} attestation JSON
     * @param nickname       optional user label (1..60 chars; trimmed)
     */
    @Transactional
    public RegisterResult finishRegistration(User user, String credentialJson, String nickname) {
        PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> pkc;
        try {
            pkc = PublicKeyCredential.parseRegistrationResponseJson(credentialJson);
        } catch (IOException e) {
            throw ApiException.badRequest("Malformed passkey registration response.");
        }

        String challenge = pkc.getResponse().getClientData().getChallenge().getBase64Url();
        WebAuthnChallenge stored = challengeStore.consume(challenge, WebAuthnChallenge.CEREMONY_REGISTER);
        if (stored == null || stored.getUserId() == null || !stored.getUserId().equals(user.getId())) {
            // No live challenge for this ceremony/user (expired, replayed, or
            // cross-user) → reject. Never distinguish which.
            throw ApiException.badRequest("Passkey registration expired or invalid. Please try again.");
        }

        PublicKeyCredentialCreationOptions request;
        try {
            request = PublicKeyCredentialCreationOptions.fromJson(stored.getRequestJson());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw ApiException.badGateway("Could not verify passkey registration.");
        }

        RegistrationResult result;
        try {
            result = relyingParty.rp().finishRegistration(FinishRegistrationOptions.builder()
                    .request(request)
                    .response(pkc)
                    .build());
        } catch (RegistrationFailedException e) {
            // Attestation/verification failure — generic message (anti-enumeration).
            log.warn("WebAuthn registration verification failed for user {}", user.getId());
            throw ApiException.badRequest("We couldn't verify that passkey. Please try again.");
        }

        String credentialId = result.getKeyId().getId().getBase64Url();
        // Belt-and-braces: the library already refuses a credential id that
        // credentialIdExists() reports, but guard the unique constraint too.
        if (credentialRepo.existsByCredentialId(credentialId)) {
            throw ApiException.conflict("That passkey is already registered.");
        }

        WebAuthnCredential row = new WebAuthnCredential();
        row.setUserId(user.getId());
        row.setCredentialId(credentialId);
        row.setUserHandle(WebAuthnRelyingParty.userHandle(user.getId()).getBase64Url());
        row.setPublicKeyCose(result.getPublicKeyCose().getBase64Url());
        row.setSignatureCount(result.getSignatureCount());
        row.setTransports(transportsCsv(result));
        row.setAaguid(result.getAaguid() != null ? result.getAaguid().getHex() : null);
        row.setNickname(cleanNickname(nickname));
        row.setCreatedAt(Instant.now());
        credentialRepo.save(row);

        return new RegisterResult(credentialId, row.getNickname());
    }

    // ---- AUTHENTICATE: options -----------------------------------------------

    /**
     * Start a pre-session assertion for a resolved user, or a DECOY for an unknown
     * identifier (anti-enumeration): an unknown user still gets a valid options
     * object with empty allowCredentials, and a decoy challenge (user_id NULL) is
     * stored so timing/shape don't reveal enrollment. The challenge is stored
     * TTL 300s either way.
     *
     * @param resolvedUser the user for the identifier, or empty for the decoy
     * @return the {@code navigator.credentials.get()} JSON
     */
    public String startAssertion(Optional<User> resolvedUser) {
        challengeStore.sweepExpired();

        StartAssertionOptions.StartAssertionOptionsBuilder builder = StartAssertionOptions.builder()
                .userVerification(UserVerificationRequirement.PREFERRED)
                .timeout(CEREMONY_TIMEOUT_MS);
        // A resolved user asserts by their opaque handle; the RP fills
        // allowCredentials from the repo. An unknown identifier gets NO handle, so
        // allowCredentials is empty — a structurally valid decoy.
        resolvedUser.ifPresent(u -> builder.userHandle(WebAuthnRelyingParty.userHandle(u.getId())));

        AssertionRequest assertionRequest = relyingParty.rp().startAssertion(builder.build());

        try {
            Long userId = resolvedUser.map(User::getId).orElse(null);
            challengeStore.store(WebAuthnChallenge.CEREMONY_ASSERT, userId,
                    assertionRequest.getPublicKeyCredentialRequestOptions().getChallenge().getBase64Url(),
                    assertionRequest.toJson());
            return assertionRequest.toCredentialsGetJson();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw ApiException.badGateway("Could not start passkey sign-in.");
        }
    }

    // ---- AUTHENTICATE: verify ------------------------------------------------

    /**
     * Verify an assertion and, on success, return the user id to mint a session
     * for. The stored assert challenge is consumed (single-use); a decoy challenge
     * (user_id NULL) can never authenticate. The library validates the signature,
     * challenge binding, origin, rpId, UV, and sign-count monotonicity (a
     * regression throws {@code AssertionFailedException}). We then bump the stored
     * counter and stamp lastUsedAt.
     *
     * @return the resolved user id + credential id, or empty on any failure
     */
    @Transactional
    @SuppressWarnings("deprecation")   // AssertionResultV2 tier — see StoredCredential javadoc.
    public Optional<AssertResult> finishAssertion(String credentialJson) {
        PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> pkc;
        try {
            pkc = PublicKeyCredential.parseAssertionResponseJson(credentialJson);
        } catch (IOException e) {
            return Optional.empty();
        }

        String challenge = pkc.getResponse().getClientData().getChallenge().getBase64Url();
        WebAuthnChallenge stored = challengeStore.consume(challenge, WebAuthnChallenge.CEREMONY_ASSERT);
        if (stored == null || stored.getUserId() == null) {
            // Expired/replayed challenge, or the anti-enumeration decoy (null user).
            // Either way there is nothing to authenticate.
            return Optional.empty();
        }

        AssertionRequest request;
        try {
            request = AssertionRequest.fromJson(stored.getRequestJson());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Optional.empty();
        }

        AssertionResultV2<StoredCredential> result;
        try {
            result = relyingParty.rp().finishAssertion(FinishAssertionOptions.builder()
                    .request(request)
                    .response(pkc)
                    .build());
        } catch (AssertionFailedException e) {
            // Signature invalid / sign-count regression / UV policy / wrong origin
            // or rpId — all land here. Do NOT distinguish.
            log.warn("WebAuthn assertion verification failed (challenge consumed)");
            return Optional.empty();
        }

        if (!result.isSuccess()) {
            return Optional.empty();
        }

        String credentialId = result.getCredential().getCredentialId().getBase64Url();
        WebAuthnCredential row = credentialRepo.findByCredentialId(credentialId).orElse(null);
        if (row == null || !row.getUserId().equals(stored.getUserId())) {
            // The verified credential must belong to the user the challenge was
            // bound to. (Belt-and-braces on top of the repo's handle filter.)
            return Optional.empty();
        }

        // Bump the monotonic sign counter + stamp usage.
        row.setSignatureCount(result.getSignatureCount());
        row.setLastUsedAt(Instant.now());
        credentialRepo.save(row);

        return Optional.of(new AssertResult(row.getUserId(), credentialId));
    }

    // ---- helpers -------------------------------------------------------------

    private static String transportsCsv(RegistrationResult result) {
        return result.getKeyId().getTransports()
                .map(set -> set.stream().map(AuthenticatorTransport::getId).collect(Collectors.joining(",")))
                .filter(s -> !s.isBlank())
                .orElse(null);
    }

    /** The WebAuthn {@code user.name} — a stable identifier the OS may show. */
    private static String displayIdentifier(User user) {
        if (user.getEmail() != null && !user.getEmail().isBlank()) return user.getEmail();
        return "user-" + user.getId();
    }

    /** The WebAuthn {@code user.displayName} — a friendly label. */
    private static String displayName(User user) {
        if (user.getPreferredName() != null && !user.getPreferredName().isBlank()) {
            return user.getPreferredName();
        }
        if (user.getName() != null && !user.getName().isBlank()) return user.getName();
        return displayIdentifier(user);
    }

    /** Trim + bound a nickname to 1..60 chars; null/blank ⇒ null. */
    public static String cleanNickname(String nickname) {
        if (nickname == null) return null;
        String n = nickname.trim();
        if (n.isEmpty()) return null;
        return n.length() > 60 ? n.substring(0, 60) : n;
    }
}
