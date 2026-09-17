package com.afterduty.service;

import com.afterduty.config.AuthProperties;
import com.afterduty.exception.StepUpRequiredException;
import com.afterduty.model.AuthAuditLog;
import com.afterduty.model.StepUpToken;
import com.afterduty.model.User;
import com.afterduty.repository.AuthAuditLogRepository;
import com.afterduty.repository.StepUpTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mint / validate / expiry / wrong-user / replay + guard tests for
 * {@link StepUpService} (auth program P1.2).
 *
 * <p>Runs against the H2 (MODE=PostgreSQL) slice DB with the real repo + entity,
 * a real {@link AuthProperties} (default Model B; one test flips it to STRICT),
 * and the real {@link AuthAuditService} so the STEP_UP_* audit rows are asserted.
 */
@DataJpaTest
@Import({StepUpService.class, AuthAuditService.class, AuthProperties.class})
@Tag("regression")
class StepUpServiceTest {

    private static final Long USER = 100L;
    private static final Long OTHER_USER = 200L;

    @Autowired
    StepUpService service;

    @Autowired
    StepUpTokenRepository repo;

    @Autowired
    AuthProperties authProperties;

    @Autowired
    AuthAuditLogRepository auditRepo;

    @BeforeEach
    void reset() {
        // Default to Model B for each test; the STRICT test flips it explicitly.
        authProperties.setMfaModel("ENROLLMENT_DEVICE_TRUST");
    }

    private static User user(Long id) {
        User u = new User();
        u.setId(id);
        u.setFirebaseUid("uid-" + id);
        return u;
    }

    // ---- mint ---------------------------------------------------------------

    @Test
    void mintStoresHashedTokenBoundToUserWith300sTtl() {
        StepUpService.MintResult res = service.mint(USER, StepUpToken.FACTOR_OTP);

        assertThat(res.expiresInSec()).isEqualTo(300);
        // The returned opaque token is 64 hex chars (32 random bytes) — NOT stored plaintext.
        assertThat(res.stepUpToken()).hasSize(64).matches("[0-9a-f]{64}");

        List<StepUpToken> rows = repo.findAll();
        assertThat(rows).hasSize(1);
        StepUpToken row = rows.get(0);
        // Stored value is the SHA-256 HASH of the token, never the token itself.
        assertThat(row.getTokenHash()).hasSize(64).isEqualTo(StepUpService.sha256Hex(res.stepUpToken()));
        assertThat(row.getTokenHash()).isNotEqualTo(res.stepUpToken());
        assertThat(row.getUserId()).isEqualTo(USER);
        assertThat(row.getConsumedAt()).isNull();
        assertThat(Duration.between(row.getCreatedAt(), row.getExpiresAt()).getSeconds()).isEqualTo(300);
    }

    // ---- validate + consume -------------------------------------------------

    @Test
    void validateAcceptsFreshTokenForCorrectUserThenConsumesIt() {
        String token = service.mint(USER, StepUpToken.FACTOR_OTP).stepUpToken();

        assertThat(service.validateAndConsume(USER, token)).isTrue();
        // Consumed (single-use).
        assertThat(repo.findAll().get(0).getConsumedAt()).isNotNull();
    }

    @Test
    void validateReplayIsRejected() {
        String token = service.mint(USER, StepUpToken.FACTOR_OTP).stepUpToken();
        assertThat(service.validateAndConsume(USER, token)).isTrue();
        // Second use of the SAME token fails — replay-proof.
        assertThat(service.validateAndConsume(USER, token)).isFalse();
    }

    @Test
    void validateWrongUserIsRejected() {
        String token = service.mint(USER, StepUpToken.FACTOR_OTP).stepUpToken();
        // A valid token for USER must NOT satisfy OTHER_USER.
        assertThat(service.validateAndConsume(OTHER_USER, token)).isFalse();
        // ...and it is NOT consumed by the failed cross-user attempt.
        assertThat(repo.findAll().get(0).getConsumedAt()).isNull();
    }

    @Test
    void validateExpiredTokenIsRejected() {
        // Seed an already-expired row directly.
        StepUpToken row = new StepUpToken();
        String token = StepUpService.generateToken();
        row.setTokenHash(StepUpService.sha256Hex(token));
        row.setUserId(USER);
        row.setFactor(StepUpToken.FACTOR_OTP);
        Instant now = Instant.now();
        row.setCreatedAt(now.minus(Duration.ofSeconds(600)));
        row.setExpiresAt(now.minus(Duration.ofSeconds(300)));   // expired 5 min ago
        repo.saveAndFlush(row);

        assertThat(service.validateAndConsume(USER, token)).isFalse();
    }

    @Test
    void validateUnknownTokenIsRejected() {
        assertThat(service.validateAndConsume(USER, "deadbeef")).isFalse();
        assertThat(service.validateAndConsume(USER, null)).isFalse();
        assertThat(service.validateAndConsume(null, "x")).isFalse();
    }

    // ---- requireFresh guard --------------------------------------------------

    @Test
    void requireFreshThrows403WhenNoHeader() {
        MockHttpServletRequest req = new MockHttpServletRequest();   // no X-Step-Up
        assertThatThrownBy(() -> service.requireFresh(user(USER), req))
                .isInstanceOf(StepUpRequiredException.class)
                .satisfies(t -> assertThat(((StepUpRequiredException) t).getAcceptedFactors())
                        .containsExactly("otp"));
        // A STEP_UP_REQUESTED failure was audited (never the token).
        assertThat(auditRepo.findByEventTypeOrderByCreatedAtDesc(AuthAuditLog.EVENT_STEP_UP_REQUESTED))
                .isNotEmpty()
                .allSatisfy(r -> assertThat(r.getOutcome()).isEqualTo("FAILURE"));
    }

    @Test
    void requireFreshThrows403WhenHeaderIsInvalid() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(StepUpService.STEP_UP_HEADER, "not-a-real-token");
        assertThatThrownBy(() -> service.requireFresh(user(USER), req))
                .isInstanceOf(StepUpRequiredException.class);
    }

    @Test
    void requireFreshPassesWithValidTokenAndConsumesIt() {
        String token = service.mint(USER, StepUpToken.FACTOR_OTP).stepUpToken();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(StepUpService.STEP_UP_HEADER, token);

        // Passes (no throw), and the token is single-use.
        service.requireFresh(user(USER), req);
        assertThat(repo.findAll().get(0).getConsumedAt()).isNotNull();

        // A second guarded call with the SAME (now consumed) token is challenged.
        MockHttpServletRequest req2 = new MockHttpServletRequest();
        req2.addHeader(StepUpService.STEP_UP_HEADER, token);
        assertThatThrownBy(() -> service.requireFresh(user(USER), req2))
                .isInstanceOf(StepUpRequiredException.class);
    }

    @Test
    void requireFreshRejectsAnotherUsersValidToken() {
        String token = service.mint(USER, StepUpToken.FACTOR_OTP).stepUpToken();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(StepUpService.STEP_UP_HEADER, token);
        // OTHER_USER presents USER's token → still challenged.
        assertThatThrownBy(() -> service.requireFresh(user(OTHER_USER), req))
                .isInstanceOf(StepUpRequiredException.class);
    }

    // ---- MFA model flag branch ----------------------------------------------

    @Test
    void strictAndModelBBothRequireFreshStepUpInPhase1() {
        // Phase 1: neither model has a device-trust exemption yet, so both require
        // a fresh step-up when no valid token is present. This asserts the flag is
        // READ (no crash under STRICT) and both branches challenge without a token.
        MockHttpServletRequest noHeader = new MockHttpServletRequest();

        authProperties.setMfaModel("ENROLLMENT_DEVICE_TRUST");
        assertThatThrownBy(() -> service.requireFresh(user(USER), noHeader))
                .isInstanceOf(StepUpRequiredException.class);

        authProperties.setMfaModel("STRICT");
        assertThat(authProperties.isStrict()).isTrue();
        MockHttpServletRequest noHeader2 = new MockHttpServletRequest();
        assertThatThrownBy(() -> service.requireFresh(user(USER), noHeader2))
                .isInstanceOf(StepUpRequiredException.class);

        // And a valid token satisfies the guard under STRICT too.
        String token = service.mint(USER, StepUpToken.FACTOR_OTP).stepUpToken();
        MockHttpServletRequest withHeader = new MockHttpServletRequest();
        withHeader.addHeader(StepUpService.STEP_UP_HEADER, token);
        service.requireFresh(user(USER), withHeader);   // no throw
    }

    @Test
    void unknownMfaModelFailsSafeToModelB() {
        authProperties.setMfaModel("bananas");
        assertThat(authProperties.getMfaModel())
                .isEqualTo(AuthProperties.MfaModel.ENROLLMENT_DEVICE_TRUST);
        assertThat(authProperties.isStrict()).isFalse();
    }
}
