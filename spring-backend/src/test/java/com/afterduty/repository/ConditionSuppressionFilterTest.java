package com.afterduty.repository;

import com.afterduty.model.Claim;
import com.afterduty.model.ConditionSuppression;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.service.ChatAgent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-14 — the read-time suppression post-filter on
 * {@link ConditionRepository#findByClaimIdAndSupersededByIsNull}: the single
 * active-generation read every user-facing surface uses. A condition whose identity
 * fingerprint has an UNLIFTED {@code condition_suppressions} row must stay excluded even
 * when a later identify run re-emits it as a fresh active row — this is exactly the
 * "deleted condition resurrects on next upload" bug. Lifting the suppression (undo)
 * restores visibility. Real H2 (PostgreSQL mode) via the JPA slice so the JPQL
 * NOT-EXISTS subquery itself is what's under test.
 */
@Tag("regression")
@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:suppressionfilter;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class ConditionSuppressionFilterTest {

    @Autowired ClaimRepository claimRepository;
    @Autowired ConditionRepository conditionRepository;
    @Autowired ConditionSuppressionRepository suppressionRepository;

    private Claim claim() {
        return claimRepository.save(Claim.builder()
                .userId(1L)
                .claimType(Claim.ClaimType.INITIAL)
                .status(Claim.ClaimStatus.DRAFT)
                .build());
    }

    private IdentifiedCondition condition(Long claimId, String name, String fingerprint, Long supersededBy) {
        IdentifiedCondition c = new IdentifiedCondition();
        c.setClaimId(claimId);
        c.setName(name);
        c.setIdentityFingerprint(fingerprint);
        c.setSupersededBy(supersededBy);
        return conditionRepository.save(c);
    }

    private ConditionSuppression suppress(Long claimId, Long conditionId, String fingerprint) {
        ConditionSuppression s = new ConditionSuppression();
        s.setClaimId(claimId);
        s.setConditionId(conditionId);
        s.setIdentityFingerprint(fingerprint);
        s.setCreatedAt(Instant.now());
        return suppressionRepository.save(s);
    }

    @Test
    void suppressedFingerprint_isExcluded_evenWhenReidentifiedActive_untilLifted() {
        Claim c = claim();
        IdentifiedCondition knee = condition(c.getId(), "Knee strain", "fp-knee", null);
        IdentifiedCondition back = condition(c.getId(), "Back pain", "fp-back", null);

        // Baseline: both active rows visible.
        assertThat(names(c.getId())).containsExactlyInAnyOrder("Knee strain", "Back pain");

        // Veteran deletes the knee via chat → suppression row (the row itself would also
        // get the sentinel; here we prove the FINGERPRINT filter alone blocks resurrection).
        ConditionSuppression s = suppress(c.getId(), knee.getId(), "fp-knee");
        assertThat(names(c.getId())).containsExactly("Back pain");

        // Next identify run re-emits the knee as a brand-new ACTIVE row (superseded_by
        // null, same identity fingerprint) — pre-fix this resurrected the deletion.
        condition(c.getId(), "Knee strain (regen)", "fp-knee", null);
        assertThat(names(c.getId())).containsExactly("Back pain");

        // Undo: lifting the suppression restores visibility of the re-identified row.
        s.setLiftedAt(Instant.now());
        suppressionRepository.save(s);
        assertThat(names(c.getId()))
                .contains("Back pain", "Knee strain (regen)", "Knee strain");
    }

    @Test
    void suppressedMarkerSentinel_hidesFingerprintlessRows() {
        Claim c = claim();
        // Legacy row without a fingerprint: the subquery can't match it — the soft delete
        // relies on the SUPPRESSED_MARKER sentinel in superseded_by instead.
        condition(c.getId(), "Legacy condition", null, ChatAgent.SUPPRESSED_MARKER);
        condition(c.getId(), "Visible condition", null, null);

        assertThat(names(c.getId())).containsExactly("Visible condition");
    }

    @Test
    void suppressionIsClaimScoped_neverHidesAnotherClaimsCondition() {
        Claim mine = claim();
        Claim theirs = claim();
        IdentifiedCondition mineKnee = condition(mine.getId(), "Knee strain", "fp-knee", null);
        condition(theirs.getId(), "Knee strain", "fp-knee", null);

        suppress(mine.getId(), mineKnee.getId(), "fp-knee");

        assertThat(names(mine.getId())).isEmpty();
        assertThat(names(theirs.getId())).containsExactly("Knee strain");
    }

    private List<String> names(Long claimId) {
        return conditionRepository.findByClaimIdAndSupersededByIsNull(claimId).stream()
                .map(IdentifiedCondition::getName)
                .toList();
    }
}
