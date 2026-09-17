package com.afterduty.service;

import com.afterduty.exception.ApiException;
import com.afterduty.exception.RecoveryNeedsSupportException;
import com.afterduty.model.DeviceCredential;
import com.afterduty.model.EmailVerificationCode;
import com.afterduty.model.User;
import com.afterduty.model.WebAuthnCredential;
import com.afterduty.repository.DeviceCredentialRepository;
import com.afterduty.repository.EmailVerificationCodeRepository;
import com.afterduty.repository.UserRepository;
import com.afterduty.repository.WebAuthnCredentialRepository;
import com.afterduty.service.webauthn.WebAuthnSessionMinter;
import com.afterduty.service.webauthn.WebAuthnUserResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Dual-channel recovery core tests (auth program P1.5 — the pinned C contract).
 *
 * <p>Runs against the H2 ({@code MODE=PostgreSQL}) slice DB with the REAL
 * {@link RecoveryService} + {@link EmailCodeService} + {@link WebAuthnUserResolver}
 * + repos + entities, so the email-code half is genuinely minted, hashed, and
 * consumed. The Firebase boundary is stubbed via a {@link StubFirebaseAuthService}:
 * <ul>
 *   <li>the phone lookup (dual-channel detection) via {@code getPhoneNumberForUid}
 *       returning a configurable phone;</li>
 *   <li>the phone PROOF via {@code verifyFreshPhoneUid} returning a scripted uid;</li>
 *   <li>the session mint via a {@link RecordingMinter}.</li>
 * </ul>
 *
 * <p>Asserts the security contract: BOTH channels are required (email-only fails,
 * phone-only fails, both succeed) and on success ALL passkeys are deleted + ALL
 * device credentials are revoked; single-channel legacy → 409; the email code is
 * scoped to the RECOVERY purpose (a sign-in code can't complete recovery).
 */
@DataJpaTest
@Import({EmailCodeService.class, RecoveryServiceTest.TestConfig.class})
@Tag("regression")
class RecoveryServiceTest {

    private static final String EMAIL = "vet@example.com";
    private static final String UID = "uid-vet";
    private static final String PHONE = "+15555550123";

    @MockitoBean
    EmailSender emailSender;

    @Autowired
    RecoveryService service;

    @Autowired
    RecordingMinter minter;

    @Autowired
    StubFirebaseAuthService firebaseAuthService;

    @Autowired
    EmailCodeService emailCodeService;

    @Autowired
    EmailVerificationCodeRepository codeRepo;

    @Autowired
    WebAuthnCredentialRepository webAuthnRepo;

    @Autowired
    DeviceCredentialRepository deviceRepo;

    @Autowired
    UserRepository userRepository;

    private User vet;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(emailCodeService, "devMode", false);
        ReflectionTestUtils.setField(emailCodeService, "sendGridApiKey", "SG.configured-key");
        when(emailSender.sendHtml(anyString(), anyString(), anyString()))
                .thenReturn(EmailSender.Result.sent("msg-1"));

        vet = userRepository.save(User.builder().email(EMAIL).name("Vet").firebaseUid(UID).build());

        // Default: a dual-channel account (email on the row + phone in Firebase),
        // and the phone proof returns the account's own uid.
        firebaseAuthService.phoneNumber = PHONE;
        firebaseAuthService.freshUid = UID;
        // Singleton beans are reused across test methods in the shared context —
        // reset the recorded mint uid so one test can't observe another's.
        minter.lastUid = null;
    }

    // ---- helpers -------------------------------------------------------------

    private static MockHttpServletRequest req() {
        return new MockHttpServletRequest();
    }

    /** Seed a fresh RECOVERY-purpose email code and return its plaintext. */
    private String seedRecoveryCode(String plaintext) {
        EmailVerificationCode row = new EmailVerificationCode();
        row.setEmail(EMAIL);
        row.setCodeHash(sha256Hex(plaintext));
        row.setExpiresAt(Instant.now().plus(Duration.ofMinutes(10)));
        row.setAttempts(0);
        row.setCreatedAt(Instant.now());
        row.setPurpose(EmailVerificationCode.PURPOSE_RECOVERY);
        return codeRepo.saveAndFlush(row) != null ? plaintext : plaintext;
    }

    private void seedPasskey() {
        WebAuthnCredential c = new WebAuthnCredential();
        c.setUserId(vet.getId());
        c.setCredentialId("cred-" + System.nanoTime());
        c.setUserHandle("handle-" + vet.getId());
        c.setPublicKeyCose("cose");
        c.setSignatureCount(1);
        c.setCreatedAt(Instant.now());
        webAuthnRepo.saveAndFlush(c);
    }

    private DeviceCredential seedDevice() {
        DeviceCredential d = new DeviceCredential();
        d.setUserId(vet.getId());
        d.setDeviceSecretHash(sha256Hex("dev-secret-" + System.nanoTime()));
        d.setDeviceName("iPhone");
        d.setPlatform("ios");
        d.setCreatedAt(Instant.now());
        return deviceRepo.saveAndFlush(d);
    }

    // ---- start (anti-enumeration) --------------------------------------------

    @Test
    void startUnknownIdentifierSendsNothingAndDoesNotThrow() {
        service.start("nobody@example.com", "1.2.3.4", req());
        assertThat(codeRepo.count()).isZero();
    }

    @Test
    void startDualChannelAccountEmailsARecoveryCode() {
        service.start(EMAIL, "1.2.3.4", req());
        // A RECOVERY-purpose code row exists for the account email.
        assertThat(codeRepo.findFirstByEmailOrderByCreatedAtDesc(EMAIL)).isPresent()
                .get()
                .satisfies(r -> assertThat(r.getPurpose()).isEqualTo(EmailVerificationCode.PURPOSE_RECOVERY));
    }

    @Test
    void startSingleChannelAccountSendsNothing() {
        // No phone in Firebase ⇒ single-channel ⇒ start is silent (anti-enum).
        firebaseAuthService.phoneNumber = null;
        service.start(EMAIL, "1.2.3.4", req());
        assertThat(codeRepo.count()).isZero();
    }

    // ---- verify: BOTH channels required --------------------------------------

    @Test
    void verifyWithBothFreshFactorsSucceedsAndRevokesBothCredentialFamilies() {
        seedPasskey();
        seedPasskey();
        DeviceCredential d1 = seedDevice();
        DeviceCredential d2 = seedDevice();
        String code = seedRecoveryCode("246810");

        RecoveryService.RecoverResult result = service.verify(EMAIL, code, "phone-id-token", req());

        // Session minted for the OWNING uid ONLY.
        assertThat(result.customToken()).isEqualTo("custom-token-for:" + UID);
        assertThat(minter.lastUid).isEqualTo(UID);
        assertThat(result.userId()).isEqualTo(vet.getId());

        // ALL passkeys hard-deleted.
        assertThat(webAuthnRepo.findByUserId(vet.getId())).isEmpty();
        // ALL device credentials soft-revoked.
        assertThat(deviceRepo.findByUserIdAndRevokedAtIsNull(vet.getId())).isEmpty();
        assertThat(deviceRepo.findById(d1.getId())).get()
                .satisfies(d -> assertThat(d.getRevokedAt()).isNotNull());
        assertThat(deviceRepo.findById(d2.getId())).get()
                .satisfies(d -> assertThat(d.getRevokedAt()).isNotNull());
    }

    @Test
    void verifyEmailOnlyFailsAndRevokesNothing() {
        seedPasskey();
        seedDevice();
        String code = seedRecoveryCode("246810");
        // Phone proof does NOT match (uid null) → email alone can't complete.
        firebaseAuthService.freshUid = null;

        assertThatThrownBy(() -> service.verify(EMAIL, code, "phone-id-token", req()))
                .isInstanceOf(ApiException.class);

        // Nothing revoked; the (single-use) email code was consumed but that alone
        // proves nothing without the phone.
        assertThat(webAuthnRepo.findByUserId(vet.getId())).hasSize(1);
        assertThat(deviceRepo.findByUserIdAndRevokedAtIsNull(vet.getId())).hasSize(1);
        assertThat(minter.lastUid).isNull();
    }

    @Test
    void verifyPhoneOnlyFailsAndRevokesNothing() {
        seedPasskey();
        seedDevice();
        // No email code seeded → the email factor fails even though the phone proof
        // would succeed.
        assertThatThrownBy(() -> service.verify(EMAIL, "000000", "phone-id-token", req()))
                .isInstanceOf(ApiException.class);

        assertThat(webAuthnRepo.findByUserId(vet.getId())).hasSize(1);
        assertThat(deviceRepo.findByUserIdAndRevokedAtIsNull(vet.getId())).hasSize(1);
        assertThat(minter.lastUid).isNull();
    }

    @Test
    void verifyPhoneProofForAnotherUidFails() {
        seedPasskey();
        String code = seedRecoveryCode("246810");
        // A valid FRESH phone token, but for a DIFFERENT account's uid → rejected.
        firebaseAuthService.freshUid = "uid-someone-else";

        assertThatThrownBy(() -> service.verify(EMAIL, code, "phone-id-token", req()))
                .isInstanceOf(ApiException.class);
        assertThat(webAuthnRepo.findByUserId(vet.getId())).hasSize(1);
    }

    @Test
    void verifyRejectsASignInPurposeCode() {
        // Seed a SIGNIN-purpose code with the same plaintext — it must NOT satisfy
        // the RECOVERY lane.
        EmailVerificationCode signin = new EmailVerificationCode();
        signin.setEmail(EMAIL);
        signin.setCodeHash(sha256Hex("135790"));
        signin.setExpiresAt(Instant.now().plus(Duration.ofMinutes(10)));
        signin.setAttempts(0);
        signin.setCreatedAt(Instant.now());
        signin.setPurpose(EmailVerificationCode.PURPOSE_SIGNIN);
        codeRepo.saveAndFlush(signin);

        assertThatThrownBy(() -> service.verify(EMAIL, "135790", "phone-id-token", req()))
                .isInstanceOf(ApiException.class);
    }

    // ---- single-channel legacy → 409 -----------------------------------------

    @Test
    void verifySingleChannelAccountIs409NeedsSupport() {
        firebaseAuthService.phoneNumber = null; // no phone ⇒ single-channel
        String code = seedRecoveryCode("246810");

        assertThatThrownBy(() -> service.verify(EMAIL, code, "phone-id-token", req()))
                .isInstanceOf(RecoveryNeedsSupportException.class);
    }

    @Test
    void verifySingleChannelWithBadEmailCodeIsGeneric400NotEnumerable() {
        // Anti-enumeration: the distinguishable 409 support-hold response must NOT
        // be reachable without a valid fresh email code. A caller who does NOT
        // control the email channel (wrong code) gets the SAME generic 400 they'd
        // get for a dual-channel account or a nonexistent account — so the 409 can
        // never be used to probe which accounts are single-channel.
        firebaseAuthService.phoneNumber = null; // no phone ⇒ single-channel
        // No valid RECOVERY code seeded → the email factor fails first.

        assertThatThrownBy(() -> service.verify(EMAIL, "000000", "phone-id-token", req()))
                .isInstanceOf(ApiException.class)
                .isNotInstanceOf(RecoveryNeedsSupportException.class);
    }

    @Test
    void verifyUnknownAccountFailsGenerically() {
        assertThatThrownBy(() -> service.verify("nobody@example.com", "000000", "tok", req()))
                .isInstanceOf(ApiException.class)
                .isNotInstanceOf(RecoveryNeedsSupportException.class);
    }

    // ---- test doubles --------------------------------------------------------

    /**
     * A {@link FirebaseAuthService} whose Firebase boundary is scriptable: the
     * dual-channel phone lookup ({@code getPhoneNumberForUid}) and the phone
     * freshness proof ({@code verifyFreshPhoneUid}) both return configured values,
     * so no real Firebase (or Firebase mock) is touched.
     */
    static class StubFirebaseAuthService extends FirebaseAuthService {
        volatile String freshUid;
        volatile String phoneNumber;

        StubFirebaseAuthService(UserRepository repo) {
            super(repo);
        }

        @Override
        public String verifyFreshPhoneUid(String idToken, long maxAuthAgeSec) {
            return freshUid;
        }

        @Override
        public String getPhoneNumberForUid(String uid) {
            return phoneNumber;
        }
    }

    /** Records the uid the session was minted for + returns a deterministic token. */
    static class RecordingMinter extends WebAuthnSessionMinter {
        volatile String lastUid;

        @Override
        public String mintCustomToken(String uid) {
            this.lastUid = uid;
            return "custom-token-for:" + uid;
        }
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {
        @org.springframework.context.annotation.Bean
        RecordingMinter recordingMinter() {
            return new RecordingMinter();
        }

        @org.springframework.context.annotation.Bean
        StubFirebaseAuthService stubFirebaseAuthService(UserRepository repo) {
            return new StubFirebaseAuthService(repo);
        }

        @org.springframework.context.annotation.Bean
        WebAuthnUserResolver webAuthnUserResolver(UserRepository repo) {
            return new WebAuthnUserResolver(repo);
        }

        @org.springframework.context.annotation.Bean
        AuthAuditService authAuditService(
                com.afterduty.repository.AuthAuditLogRepository repo) {
            return new AuthAuditService(repo);
        }

        @org.springframework.context.annotation.Bean
        RecoveryService recoveryService(WebAuthnUserResolver resolver,
                                        EmailCodeService emailCodeService,
                                        StubFirebaseAuthService firebaseAuthService,
                                        RecordingMinter minter,
                                        WebAuthnCredentialRepository webAuthnRepo,
                                        DeviceCredentialRepository deviceRepo,
                                        AuthAuditService audit) {
            return new RecoveryService(resolver, emailCodeService, firebaseAuthService,
                    minter, webAuthnRepo, deviceRepo, audit);
        }
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
