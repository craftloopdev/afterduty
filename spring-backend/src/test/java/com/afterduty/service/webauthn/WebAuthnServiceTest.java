package com.afterduty.service.webauthn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.config.AuthProperties;
import com.afterduty.model.User;
import com.afterduty.model.WebAuthnChallenge;
import com.afterduty.model.WebAuthnCredential;
import com.afterduty.repository.WebAuthnChallengeRepository;
import com.afterduty.repository.WebAuthnCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full register-then-assert round-trip tests for {@link WebAuthnService} using a
 * deterministic in-process {@link SoftwareAuthenticator} (auth program P1.3).
 *
 * <p>Runs against the H2 (MODE=PostgreSQL) slice DB with the REAL RP verifier
 * (Yubico library), the real credential + challenge repositories, and an
 * env-driven {@link AuthProperties} whose rpId/origin are the localhost test
 * values. These tests prove the library actually verifies our ceremonies — a
 * good round trip persists a credential and re-authenticates it, and each of the
 * hard requirements (challenge single-use, TTL expiry, sign-count regression,
 * wrong origin, wrong rpId, cross-user) is exercised as a REJECTION.
 */
@DataJpaTest
@Tag("regression")
class WebAuthnServiceTest {

    private static final String RP_ID = "localhost";
    private static final String ORIGIN = "https://localhost";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    WebAuthnCredentialRepository credentialRepo;

    @Autowired
    WebAuthnChallengeRepository challengeRepo;

    // The service is built by hand from the autowired repos + a test-local
    // AuthProperties (rpId/origin = localhost) so there is no bean-wiring
    // ambiguity with the production AuthProperties bound from application.yml.
    private WebAuthnService service;
    private User user;

    @BeforeEach
    void setup() {
        AuthProperties props = new AuthProperties();
        props.getWebauthn().setRpId(RP_ID);
        props.getWebauthn().setRpName("After Duty Test");
        props.getWebauthn().setOrigin(ORIGIN);
        WebAuthnRelyingParty rp = new WebAuthnRelyingParty(props, new DbCredentialRepository(credentialRepo));
        service = new WebAuthnService(rp, credentialRepo, new WebAuthnChallengeStore(challengeRepo));

        user = User.builder().id(42L).email("vet@example.com").name("Vet").firebaseUid("uid-42").build();
    }

    // ---- helpers -------------------------------------------------------------

    private String challengeFrom(String optionsJson) {
        try {
            JsonNode root = MAPPER.readTree(optionsJson);
            return root.path("publicKey").path("challenge").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String userHandleB64Url() {
        return WebAuthnRelyingParty.userHandle(user.getId()).getBase64Url();
    }

    /** Register a passkey with the given authenticator; returns its credential id. */
    private String register(SoftwareAuthenticator auth) {
        String createOptions = service.startRegistration(user);
        String challenge = challengeFrom(createOptions);
        String createResp = auth.makeCreateResponseJson(challenge, ORIGIN, RP_ID);
        WebAuthnService.RegisterResult reg = service.finishRegistration(user, createResp, "My Key");
        return reg.credentialId();
    }

    // ---- happy path: full round trip ----------------------------------------

    @Test
    void registerThenAssertRoundTripSucceeds() {
        SoftwareAuthenticator auth = new SoftwareAuthenticator();

        // REGISTER
        String credentialId = register(auth);
        assertThat(credentialId).isEqualTo(auth.credentialIdB64Url());
        List<WebAuthnCredential> creds = credentialRepo.findAll();
        assertThat(creds).hasSize(1);
        assertThat(creds.get(0).getUserId()).isEqualTo(user.getId());
        assertThat(creds.get(0).getNickname()).isEqualTo("My Key");
        assertThat(creds.get(0).getSignatureCount()).isEqualTo(0L);
        // The register challenge was consumed (single-use).
        assertThat(challengeRepo.findAll()).isEmpty();

        // ASSERT
        String getOptions = service.startAssertion(Optional.of(user));
        String getChallenge = challengeFrom(getOptions);
        String getResp = auth.makeGetResponseJson(getChallenge, ORIGIN, RP_ID, userHandleB64Url());
        Optional<WebAuthnService.AssertResult> res = service.finishAssertion(getResp);

        assertThat(res).isPresent();
        assertThat(res.get().userId()).isEqualTo(user.getId());
        assertThat(res.get().credentialId()).isEqualTo(credentialId);
        // Sign counter bumped from 0 → 1, lastUsedAt stamped.
        WebAuthnCredential after = credentialRepo.findByCredentialId(credentialId).orElseThrow();
        assertThat(after.getSignatureCount()).isEqualTo(1L);
        assertThat(after.getLastUsedAt()).isNotNull();
        // Assert challenge consumed too.
        assertThat(challengeRepo.findAll()).isEmpty();
    }

    // ---- challenge single-use -----------------------------------------------

    @Test
    void assertChallengeIsSingleUse() {
        SoftwareAuthenticator auth = new SoftwareAuthenticator();
        register(auth);

        String getOptions = service.startAssertion(Optional.of(user));
        String getChallenge = challengeFrom(getOptions);
        String getResp = auth.makeGetResponseJson(getChallenge, ORIGIN, RP_ID, userHandleB64Url());

        assertThat(service.finishAssertion(getResp)).isPresent();
        // Replaying the SAME assertion (same challenge) fails — the challenge row
        // was deleted on first consume.
        assertThat(service.finishAssertion(getResp)).isEmpty();
    }

    // ---- challenge TTL expiry -----------------------------------------------

    @Test
    void expiredChallengeIsRejected() {
        SoftwareAuthenticator auth = new SoftwareAuthenticator();
        register(auth);

        String getOptions = service.startAssertion(Optional.of(user));
        String getChallenge = challengeFrom(getOptions);

        // Force the stored challenge to be expired.
        WebAuthnChallenge row = challengeRepo.findByChallenge(getChallenge).orElseThrow();
        row.setExpiresAt(Instant.now().minus(Duration.ofSeconds(1)));
        challengeRepo.saveAndFlush(row);

        String getResp = auth.makeGetResponseJson(getChallenge, ORIGIN, RP_ID, userHandleB64Url());
        assertThat(service.finishAssertion(getResp)).isEmpty();
    }

    // ---- sign-count regression = cloned authenticator ------------------------

    @Test
    void signCountRegressionIsRejected() {
        SoftwareAuthenticator auth = new SoftwareAuthenticator();
        register(auth);

        // First assertion advances the stored counter to 5.
        String o1 = service.startAssertion(Optional.of(user));
        String c1 = challengeFrom(o1);
        String r1 = auth.makeGetResponseJson(c1, ORIGIN, RP_ID, userHandleB64Url(), 5);
        assertThat(service.finishAssertion(r1)).isPresent();
        assertThat(credentialRepo.findByCredentialId(auth.credentialIdB64Url()).orElseThrow()
                .getSignatureCount()).isEqualTo(5L);

        // A later assertion with a NON-increasing counter (3 <= 5) is a cloned
        // authenticator → the verifier rejects it.
        String o2 = service.startAssertion(Optional.of(user));
        String c2 = challengeFrom(o2);
        String r2 = auth.makeGetResponseJson(c2, ORIGIN, RP_ID, userHandleB64Url(), 3);
        assertThat(service.finishAssertion(r2)).isEmpty();
        // The stored counter is NOT rolled back by the failed attempt.
        assertThat(credentialRepo.findByCredentialId(auth.credentialIdB64Url()).orElseThrow()
                .getSignatureCount()).isEqualTo(5L);
    }

    // ---- wrong origin --------------------------------------------------------

    @Test
    void wrongOriginIsRejected() {
        SoftwareAuthenticator auth = new SoftwareAuthenticator();
        register(auth);

        String getOptions = service.startAssertion(Optional.of(user));
        String getChallenge = challengeFrom(getOptions);
        // The authenticator signs an origin that is NOT the configured one.
        String getResp = auth.makeGetResponseJson(getChallenge, "https://evil.example.com", RP_ID, userHandleB64Url());
        assertThat(service.finishAssertion(getResp)).isEmpty();
    }

    // ---- wrong rpId ----------------------------------------------------------

    @Test
    void wrongRpIdIsRejected() {
        SoftwareAuthenticator auth = new SoftwareAuthenticator();
        register(auth);

        String getOptions = service.startAssertion(Optional.of(user));
        String getChallenge = challengeFrom(getOptions);
        // authData carries the SHA-256 of a different rpId → rpIdHash mismatch.
        String getResp = auth.makeGetResponseJson(getChallenge, ORIGIN, "evil.example.com", userHandleB64Url());
        assertThat(service.finishAssertion(getResp)).isEmpty();
    }

    // ---- cross-user credential ----------------------------------------------

    @Test
    void crossUserCredentialIsRejected() {
        SoftwareAuthenticator auth = new SoftwareAuthenticator();
        register(auth);   // credential belongs to user 42

        // A DIFFERENT user (id 99) starts an assertion — its allowCredentials is
        // empty (no passkeys) and its user handle differs, so user 42's credential
        // must not authenticate user 99's ceremony.
        User other = User.builder().id(99L).email("other@example.com").name("Other").firebaseUid("uid-99").build();
        String getOptions = service.startAssertion(Optional.of(other));
        String getChallenge = challengeFrom(getOptions);
        // Present user 42's credential + user 42's handle against user 99's challenge.
        String getResp = auth.makeGetResponseJson(getChallenge, ORIGIN, RP_ID, userHandleB64Url());
        assertThat(service.finishAssertion(getResp)).isEmpty();
    }

    // ---- anti-enumeration: unknown identifier still returns options ----------

    @Test
    void decoyAssertionForUnknownUserReturnsValidOptionsButNeverAuthenticates() {
        // No user resolved (unknown identifier). startAssertion still returns a
        // valid options object with empty allowCredentials, and stores a decoy
        // challenge (user_id NULL).
        String getOptions = service.startAssertion(Optional.empty());
        assertThat(getOptions).contains("\"challenge\"");
        // allowCredentials is absent/empty for the decoy.
        try {
            JsonNode pk = MAPPER.readTree(getOptions).path("publicKey");
            JsonNode allow = pk.path("allowCredentials");
            assertThat(allow.isMissingNode() || allow.size() == 0).isTrue();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        WebAuthnChallenge decoy = challengeRepo.findAll().get(0);
        assertThat(decoy.getUserId()).isNull();
        assertThat(decoy.getCeremony()).isEqualTo(WebAuthnChallenge.CEREMONY_ASSERT);

        // Even a well-formed assertion against the decoy challenge cannot mint a
        // session — the decoy has no bound user.
        SoftwareAuthenticator auth = new SoftwareAuthenticator();
        String getChallenge = challengeFrom(getOptions);
        String getResp = auth.makeGetResponseJson(getChallenge, ORIGIN, RP_ID, userHandleB64Url());
        assertThat(service.finishAssertion(getResp)).isEmpty();
    }

    // ---- a failed registration still consumes the challenge (single-use) -----

    @Test
    void failedRegistrationStillConsumesChallenge() {
        // Start a real register ceremony, then submit an attestation the verifier
        // rejects (wrong rpId in authData). The challenge must be burned so the
        // same options can't be retried.
        String createOptions = service.startRegistration(user);
        String challenge = challengeFrom(createOptions);
        assertThat(challengeRepo.findByChallenge(challenge)).isPresent();

        SoftwareAuthenticator auth = new SoftwareAuthenticator();
        String badResp = auth.makeCreateResponseJson(challenge, ORIGIN, "evil.example.com");
        try {
            service.finishRegistration(user, badResp, "x");
        } catch (RuntimeException expected) {
            // verification failed → ApiException
        }
        // The challenge row is gone (single-use), so a replay finds nothing.
        assertThat(challengeRepo.findByChallenge(challenge)).isEmpty();
        // And no credential was persisted.
        assertThat(credentialRepo.findAll()).isEmpty();
    }

    // ---- register options carry excludeCredentials --------------------------

    @Test
    void registerOptionsExcludeExistingCredentials() {
        SoftwareAuthenticator auth = new SoftwareAuthenticator();
        String credentialId = register(auth);

        // A second register/options for the same user must list the just-enrolled
        // credential in excludeCredentials (so the same authenticator isn't re-enrolled).
        String createOptions = service.startRegistration(user);
        try {
            JsonNode exclude = MAPPER.readTree(createOptions).path("publicKey").path("excludeCredentials");
            assertThat(exclude.isArray()).isTrue();
            boolean found = false;
            for (JsonNode n : exclude) {
                if (credentialId.equals(n.path("id").asText())) found = true;
            }
            assertThat(found).isTrue();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
