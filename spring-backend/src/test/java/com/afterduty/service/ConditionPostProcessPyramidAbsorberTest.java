package com.afterduty.service;

import com.afterduty.model.Claim;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.model.User;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.ConditionRepository;
import com.afterduty.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-1 adjunct — the highest-rated member of every pyramid group must keep a
 * BLANK pyramidReason so PyramidingRules.plan counts it as the absorber.
 * Before the fix, detectPyramiding stamped a reason on EVERY group member,
 * silently dropping whole groups (e.g. all mental-health conditions) from the
 * combined rating.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ConditionPostProcessPyramidAbsorberTest {

    @Autowired private ConditionPostProcessService service;
    @Autowired private ConditionRepository conditionRepository;
    @Autowired private ClaimRepository claimRepository;
    @Autowired private UserRepository userRepository;

    private Long seedClaim() {
        User user = userRepository.save(User.builder()
                .firebaseUid("pyramid-absorber-test")
                .email("pyramid-absorber@test.local")
                .name("Pyramid Tester")
                .build());
        Claim claim = new Claim();
        claim.setUserId(user.getId());
        return claimRepository.save(claim).getId();
    }

    private IdentifiedCondition cond(Long claimId, String name, String vasrd, Integer rating, Double conf) {
        IdentifiedCondition c = new IdentifiedCondition();
        c.setClaimId(claimId);
        c.setName(name);
        c.setVasrdCode(vasrd);
        c.setBodySystem("Mental Disorders");
        c.setEstimatedRating(rating);
        c.setConfidence(conf);
        return conditionRepository.save(c);
    }

    @Test
    void highestRatedGroupMemberKeepsBlankReason_othersNameTheAbsorber() {
        Long claimId = seedClaim();
        IdentifiedCondition ptsd = cond(claimId, "PTSD", "9411", 70, 0.9);
        IdentifiedCondition anxiety = cond(claimId, "Generalized Anxiety Disorder", "9400", 50, 0.8);

        service.detectPyramiding(claimId);

        IdentifiedCondition ptsdAfter = conditionRepository.findById(ptsd.getId()).orElseThrow();
        IdentifiedCondition anxietyAfter = conditionRepository.findById(anxiety.getId()).orElseThrow();

        // Both grouped, but ONLY the absorbed one carries a reason (= excluded).
        assertThat(ptsdAfter.getPyramidGroup()).isEqualTo("mental_health");
        assertThat(anxietyAfter.getPyramidGroup()).isEqualTo("mental_health");
        assertThat(ptsdAfter.getPyramidReason()).isNull();
        assertThat(anxietyAfter.getPyramidReason()).contains("PTSD");
    }

    @Test
    void singleMemberGroupIsNeverExcluded() {
        Long claimId = seedClaim();
        IdentifiedCondition ptsd = cond(claimId, "PTSD", "9411", 70, 0.9);
        // A second, non-grouped condition so detectPyramiding doesn't early-return.
        cond(claimId, "Right knee strain", "5260", 10, 0.7);

        service.detectPyramiding(claimId);

        IdentifiedCondition after = conditionRepository.findById(ptsd.getId()).orElseThrow();
        assertThat(after.getPyramidReason()).isNull();
    }

    @Test
    void unratedMembersLoseToRatedOnes() {
        Long claimId = seedClaim();
        IdentifiedCondition rated = cond(claimId, "Major Depressive Disorder", "9434", 30, 0.6);
        IdentifiedCondition unrated = cond(claimId, "Adjustment Disorder", "9440", null, 0.9);

        service.detectPyramiding(claimId);

        List<IdentifiedCondition> after = conditionRepository.findByClaimIdAndSupersededByIsNull(claimId);
        IdentifiedCondition ratedAfter = after.stream().filter(c -> c.getId().equals(rated.getId())).findFirst().orElseThrow();
        IdentifiedCondition unratedAfter = after.stream().filter(c -> c.getId().equals(unrated.getId())).findFirst().orElseThrow();
        assertThat(ratedAfter.getPyramidReason()).isNull();
        assertThat(unratedAfter.getPyramidReason()).isNotBlank();
    }
}
