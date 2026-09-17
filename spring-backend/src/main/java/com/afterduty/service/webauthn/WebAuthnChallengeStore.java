package com.afterduty.service.webauthn;

import com.afterduty.model.WebAuthnChallenge;
import com.afterduty.repository.WebAuthnChallengeRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Server-side store for in-flight WebAuthn challenges (auth program P1.3), with
 * single-use consume semantics that survive a failed finish.
 *
 * <p><b>Why a separate component / {@code REQUIRES_NEW}:</b> the finish steps
 * ({@code finishRegistration} throwing {@code ApiException} on a bad attestation)
 * run in their own transaction; if consume ran in THAT transaction, a thrown
 * {@code RuntimeException} would roll the delete back and the challenge would be
 * replayable. Consuming in a {@code REQUIRES_NEW} transaction commits the delete
 * immediately and independently, so single-use is a hard invariant even when
 * verification then fails. (Assertion returns empty rather than throwing, but the
 * same guarantee is applied uniformly.)
 */
@Component
public class WebAuthnChallengeStore {

    /** Challenge TTL — 300s, matching the step-up store. */
    static final int CHALLENGE_TTL_SEC = 300;

    private final WebAuthnChallengeRepository repo;

    public WebAuthnChallengeStore(WebAuthnChallengeRepository repo) {
        this.repo = repo;
    }

    /** Persist a fresh challenge (its own committed transaction). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void store(String ceremony, Long userId, String challenge, String requestJson) {
        Instant now = Instant.now();
        WebAuthnChallenge row = new WebAuthnChallenge();
        row.setCeremony(ceremony);
        row.setUserId(userId);
        row.setChallenge(challenge);
        row.setRequestJson(requestJson);
        row.setCreatedAt(now);
        row.setExpiresAt(now.plus(Duration.ofSeconds(CHALLENGE_TTL_SEC)));
        repo.saveAndFlush(row);
    }

    /**
     * Look a challenge up, DELETE it (single-use — committed even if the caller
     * then rolls back), and return it only if it matches the expected ceremony and
     * is unexpired; otherwise {@code null}. Consuming on every path (wrong ceremony,
     * expired) burns the row so it can't be retried.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WebAuthnChallenge consume(String challenge, String expectedCeremony) {
        if (challenge == null || challenge.isBlank()) {
            return null;
        }
        Optional<WebAuthnChallenge> found = repo.findByChallenge(challenge);
        if (found.isEmpty()) {
            return null;
        }
        WebAuthnChallenge row = found.get();
        repo.delete(row);
        repo.flush();
        if (!expectedCeremony.equals(row.getCeremony())) {
            return null;
        }
        if (Instant.now().isAfter(row.getExpiresAt())) {
            return null;
        }
        return row;
    }

    /** Opportunistic sweep of long-dead rows (no scheduler exists; sweep at mint). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sweepExpired() {
        repo.deleteExpiredBefore(Instant.now().minus(Duration.ofDays(1)));
    }
}
