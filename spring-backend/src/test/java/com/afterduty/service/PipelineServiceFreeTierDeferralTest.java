package com.afterduty.service;

import com.afterduty.config.SubscriptionProperties;
import com.afterduty.config.UsageProperties;
import com.afterduty.model.Claim;
import com.afterduty.model.EvidenceItem;
import com.afterduty.model.User;
import com.afterduty.repository.AiCallLogRepository;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.ConditionRepository;
import com.afterduty.repository.EvidenceRepository;
import com.afterduty.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Item D (§6 A1) — the free-tier spend cap must ride the SHIPPED deferral
 * machinery end to end: real {@link UsageService} (150¢ free cap selection) →
 * real {@link UsageGuard} (throws at the cap) → real {@link PipelineService}
 * (marks the evidence {@code deferred_usage_limit} with the
 * "Paused — plan limit reached. Resumes &lt;date&gt;." message that
 * {@code UsageResetJob} re-queues on the 1st).
 *
 * <p>Only the repositories and downstream AI services are mocked — the whole
 * cap-decision chain is the production code.
 */
@Tag("regression")
class PipelineServiceFreeTierDeferralTest {

    AiCallLogRepository ledger;
    UserRepository userRepository;
    ClaimRepository claimRepository;
    EvidenceRepository evidenceRepository;
    UsageProperties props;
    SubscriptionProperties subs;
    PipelineService pipeline;

    EvidenceItem evidence;
    Claim claim;

    @BeforeEach
    void setUp() {
        ledger = mock(AiCallLogRepository.class);
        userRepository = mock(UserRepository.class);
        claimRepository = mock(ClaimRepository.class);
        evidenceRepository = mock(EvidenceRepository.class);

        props = new UsageProperties();
        props.setLimitCents(400);
        props.setFreeLimitCents(150);
        props.setEnabled(true);

        subs = new SubscriptionProperties();
        subs.setFreeAnalysisTier("a1");

        Clock clock = Clock.fixed(Instant.parse("2026-04-15T12:00:00Z"), ZoneOffset.UTC);
        UsageService usageService = new UsageService(ledger, props, userRepository, clock, subs);
        UsageGuard guard = new UsageGuard(usageService, props);

        pipeline = new PipelineService(
                mock(ClaudeSynthesisService.class),
                mock(DocumentStorageService.class),
                mock(ConditionPostProcessService.class),
                mock(PipelineVerifierService.class),
                claimRepository, evidenceRepository,
                mock(AtomRepository.class),
                mock(ConditionRepository.class),
                guard,
                mock(ServiceHistoryAdjudicationService.class));

        evidence = EvidenceItem.builder()
                .id(99L).claimId(10L).filename("dd214.pdf")
                .processingStatus("pending").build();
        claim = Claim.builder().id(10L).userId(1L).build();
        when(evidenceRepository.findById(99L)).thenReturn(Optional.of(evidence));
        when(claimRepository.findById(10L)).thenReturn(Optional.of(claim));
    }

    private void userIs(User u) {
        when(userRepository.findById(1L)).thenReturn(Optional.of(u));
    }

    private void spent(String dollars) {
        when(ledger.totalCostByUserIdInPeriod(eq(1L), any(), any()))
                .thenReturn(new BigDecimal(dollars));
    }

    private static User freeUser() {
        return User.builder().id(1L).email("free@example.com").name("Free").build();
    }

    private static User proUser() {
        return User.builder().id(1L).email("pro@example.com").name("Pro")
                .subscriptionExpiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .build();
    }

    @Test
    void freeUser_atFreeCap_isDeferredWithPausedResumeMessage() {
        userIs(freeUser());
        spent("1.50"); // == 150¢ free cap

        pipeline.processEvidence(99L, 1L);

        assertThat(evidence.getProcessingStatus()).isEqualTo("deferred_usage_limit");
        assertThat(evidence.getProcessingMessage())
                .contains("Paused — plan limit reached. Resumes 2026-05-01T00:00:00Z");
    }

    @Test
    void freeUser_underFreeCap_proceedsToQueued() {
        userIs(freeUser());
        spent("1.49");

        pipeline.processEvidence(99L, 1L);

        assertThat(evidence.getProcessingStatus()).isEqualTo("queued");
    }

    @Test
    void proUser_sameSpend_notDeferred_proCapUnchanged() {
        userIs(proUser());
        spent("1.50"); // over the free cap, well under the 400¢ Pro cap

        pipeline.processEvidence(99L, 1L);

        assertThat(evidence.getProcessingStatus()).isEqualTo("queued");
    }

    @Test
    void proUser_atProCap_stillDeferredAt400() {
        userIs(proUser());
        spent("4.00");

        pipeline.processEvidence(99L, 1L);

        assertThat(evidence.getProcessingStatus()).isEqualTo("deferred_usage_limit");
        assertThat(evidence.getProcessingMessage())
                .contains("Paused — plan limit reached. Resumes 2026-05-01T00:00:00Z");
    }
}
