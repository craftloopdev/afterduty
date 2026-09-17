package com.afterduty.service;

import com.afterduty.model.DeviceCredential;
import com.afterduty.model.User;
import com.afterduty.repository.DeviceCredentialRepository;
import com.afterduty.repository.UserRepository;
import com.afterduty.service.webauthn.WebAuthnSessionMinter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Crypto + lifecycle tests for {@link DeviceCredentialService} (auth program P1.4,
 * the B2 device-bound-token contract).
 *
 * <p>Runs against the H2 (MODE=PostgreSQL) slice DB with the real
 * {@link DeviceCredentialRepository} + {@link UserRepository} + entities, and a
 * STUB {@link WebAuthnSessionMinter} that records the uid it was asked to mint for
 * (so we assert the session is minted for the OWNING uid only, without touching
 * Firebase).
 *
 * <p>Asserts the security invariants: enroll returns the secret ONCE and stores
 * ONLY its SHA-256 hash (never the plaintext); the happy exchange mints a token +
 * bumps last_used_at; a wrong secret is rejected; a revoked row never exchanges; a
 * cross-user deviceCredentialId cannot be exchanged with another user's secret.
 */
@DataJpaTest
@Import({DeviceCredentialService.class, DeviceCredentialServiceTest.StubMinterConfig.class})
@Tag("regression")
class DeviceCredentialServiceTest {

    @Autowired
    DeviceCredentialService service;

    @Autowired
    DeviceCredentialRepository repo;

    @Autowired
    UserRepository userRepository;

    @Autowired
    RecordingMinter minter;

    /** A stub minter that records the uid + returns a deterministic token. */
    static class RecordingMinter extends WebAuthnSessionMinter {
        volatile String lastUid;

        @Override
        public String mintCustomToken(String uid) {
            this.lastUid = uid;
            return "custom-token-for:" + uid;
        }
    }

    @TestConfiguration
    static class StubMinterConfig {
        @Bean
        RecordingMinter recordingMinter() {
            return new RecordingMinter();
        }
    }

    private User persistUser(String email, String uid) {
        User u = new User();
        u.setEmail(email);
        u.setName(email);
        u.setFirebaseUid(uid);
        u.setRole("veteran");
        u.setCreatedAt(Instant.now());
        return userRepository.saveAndFlush(u);
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- enroll --------------------------------------------------------------

    @Test
    void enrollReturnsSecretOnceAndStoresOnlyItsHash() {
        User user = persistUser("vet@example.com", "uid-1");

        DeviceCredentialService.EnrollResult res =
                service.enroll(user, "iPhone 15", "ios");

        assertThat(res.deviceSecret()).isNotBlank();
        assertThat(res.deviceCredentialId()).isNotNull();

        DeviceCredential row = repo.findById(res.deviceCredentialId()).orElseThrow();
        // The stored value is the HASH of the returned secret — NEVER the plaintext.
        assertThat(row.getDeviceSecretHash()).isEqualTo(sha256Hex(res.deviceSecret()));
        assertThat(row.getDeviceSecretHash()).isNotEqualTo(res.deviceSecret());
        assertThat(row.getUserId()).isEqualTo(user.getId());
        assertThat(row.getPlatform()).isEqualTo("ios");
        assertThat(row.getDeviceName()).isEqualTo("iPhone 15");
        assertThat(row.getRevokedAt()).isNull();
        assertThat(row.getLastUsedAt()).isNull();
    }

    @Test
    void enrollNormalizesPlatformAndDefaultsToIos() {
        User user = persistUser("vet2@example.com", "uid-2");
        DeviceCredentialService.EnrollResult a = service.enroll(user, null, "ANDROID");
        DeviceCredentialService.EnrollResult b = service.enroll(user, "  ", "garbage");

        assertThat(repo.findById(a.deviceCredentialId()).orElseThrow().getPlatform()).isEqualTo("android");
        // Unknown platform falls back to ios; blank device name becomes null.
        DeviceCredential bRow = repo.findById(b.deviceCredentialId()).orElseThrow();
        assertThat(bRow.getPlatform()).isEqualTo("ios");
        assertThat(bRow.getDeviceName()).isNull();
    }

    @Test
    void twoEnrollmentsProduceDistinctSecrets() {
        User user = persistUser("vet3@example.com", "uid-3");
        DeviceCredentialService.EnrollResult a = service.enroll(user, "A", "ios");
        DeviceCredentialService.EnrollResult b = service.enroll(user, "B", "ios");
        assertThat(a.deviceSecret()).isNotEqualTo(b.deviceSecret());
        assertThat(repo.findById(a.deviceCredentialId()).orElseThrow().getDeviceSecretHash())
                .isNotEqualTo(repo.findById(b.deviceCredentialId()).orElseThrow().getDeviceSecretHash());
    }

    // ---- exchange: happy -----------------------------------------------------

    @Test
    void exchangeHappyMintsTokenForOwningUidAndBumpsLastUsed() {
        User user = persistUser("vet4@example.com", "uid-4");
        DeviceCredentialService.EnrollResult enrolled = service.enroll(user, "iPhone", "ios");

        Optional<DeviceCredentialService.ExchangeResult> res =
                service.exchange(enrolled.deviceCredentialId(), enrolled.deviceSecret());

        assertThat(res).isPresent();
        assertThat(res.get().userId()).isEqualTo(user.getId());
        assertThat(res.get().customToken()).isEqualTo("custom-token-for:uid-4");
        // Minted for the OWNING uid only.
        assertThat(minter.lastUid).isEqualTo("uid-4");
        // last_used_at bumped.
        assertThat(repo.findById(enrolled.deviceCredentialId()).orElseThrow().getLastUsedAt()).isNotNull();
    }

    // ---- exchange: wrong secret (constant-time reject) -----------------------

    @Test
    void exchangeWrongSecretRejected() {
        User user = persistUser("vet5@example.com", "uid-5");
        DeviceCredentialService.EnrollResult enrolled = service.enroll(user, "iPhone", "ios");

        Optional<DeviceCredentialService.ExchangeResult> res =
                service.exchange(enrolled.deviceCredentialId(), "not-the-secret");

        assertThat(res).isEmpty();
        // No token minted, last_used_at untouched.
        assertThat(minter.lastUid).isNull();
        assertThat(repo.findById(enrolled.deviceCredentialId()).orElseThrow().getLastUsedAt()).isNull();
    }

    @Test
    void exchangeUnknownIdAndBlankSecretRejected() {
        assertThat(service.exchange(999_999L, "whatever")).isEmpty();
        assertThat(service.exchange(null, "whatever")).isEmpty();
        assertThat(service.exchange(1L, "  ")).isEmpty();
    }

    // ---- exchange: revoked ---------------------------------------------------

    @Test
    void exchangeRevokedRowRejected() {
        User user = persistUser("vet6@example.com", "uid-6");
        DeviceCredentialService.EnrollResult enrolled = service.enroll(user, "iPhone", "ios");

        // Revoke it, then a correct secret must STILL be rejected.
        service.revoke(enrolled.deviceCredentialId(), user.getId());

        Optional<DeviceCredentialService.ExchangeResult> res =
                service.exchange(enrolled.deviceCredentialId(), enrolled.deviceSecret());

        assertThat(res).isEmpty();
        assertThat(minter.lastUid).isNull();
    }

    // ---- exchange: cross-user ------------------------------------------------

    @Test
    void exchangeCrossUserIdWithOtherSecretRejected() {
        User a = persistUser("a@example.com", "uid-a");
        User b = persistUser("b@example.com", "uid-b");
        DeviceCredentialService.EnrollResult aCred = service.enroll(a, "A phone", "ios");
        DeviceCredentialService.EnrollResult bCred = service.enroll(b, "B phone", "ios");

        // Presenting user B's credential id together with user A's secret must fail:
        // the hash of A's secret does not match B's stored hash (constant-time compare).
        Optional<DeviceCredentialService.ExchangeResult> res =
                service.exchange(bCred.deviceCredentialId(), aCred.deviceSecret());

        assertThat(res).isEmpty();
        assertThat(minter.lastUid).isNull();
    }

    // ---- manage: list + revoke ----------------------------------------------

    @Test
    void listActiveExcludesRevoked() {
        User user = persistUser("vet7@example.com", "uid-7");
        DeviceCredentialService.EnrollResult live = service.enroll(user, "Live", "ios");
        DeviceCredentialService.EnrollResult dead = service.enroll(user, "Dead", "ios");
        service.revoke(dead.deviceCredentialId(), user.getId());

        assertThat(service.listActive(user.getId()))
                .extracting(DeviceCredentialService.CredentialSummary::id)
                .containsExactly(live.deviceCredentialId());
    }

    @Test
    void revokeIsOwnershipScopedAndIdempotent() {
        User owner = persistUser("owner@example.com", "uid-owner");
        User other = persistUser("other@example.com", "uid-other");
        DeviceCredentialService.EnrollResult cred = service.enroll(owner, "phone", "ios");

        // Another user cannot revoke it.
        assertThat(service.revoke(cred.deviceCredentialId(), other.getId())).isEmpty();
        assertThat(repo.findById(cred.deviceCredentialId()).orElseThrow().getRevokedAt()).isNull();

        // Owner revokes; a second revoke keeps the original revoked_at (idempotent).
        Instant firstRevoke = service.revoke(cred.deviceCredentialId(), owner.getId())
                .orElseThrow().getRevokedAt();
        assertThat(firstRevoke).isNotNull();
        Instant secondRevoke = service.revoke(cred.deviceCredentialId(), owner.getId())
                .orElseThrow().getRevokedAt();
        assertThat(secondRevoke).isEqualTo(firstRevoke);
    }
}
