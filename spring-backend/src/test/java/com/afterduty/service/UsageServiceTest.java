package com.afterduty.service;

import com.afterduty.config.SubscriptionProperties;
import com.afterduty.config.UsageProperties;
import com.afterduty.dto.UsageBreakdownResponse;
import com.afterduty.dto.UsageResponse;
import com.afterduty.model.User;
import com.afterduty.repository.AiCallLogRepository;
import com.afterduty.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UsageServiceTest {

    AiCallLogRepository repo;
    UsageProperties props;
    UserRepository userRepository;
    Clock clock;
    SubscriptionProperties subscriptionProps;
    UsageService svc;

    @BeforeEach
    void setUp() {
        repo = mock(AiCallLogRepository.class);
        userRepository = mock(UserRepository.class);
        props = new UsageProperties();
        props.setLimitCents(400);
        props.setFreeLimitCents(150);
        props.setEnabled(true);
        subscriptionProps = new SubscriptionProperties(); // default: off
        clock = Clock.fixed(Instant.parse("2026-04-15T12:00:00Z"), ZoneOffset.UTC);
        svc = new UsageService(repo, props, userRepository, clock, subscriptionProps);
    }

    @Test
    void zeroSpendReportsZeroPercent() {
        when(repo.totalCostByUserIdInPeriod(anyLong(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        UsageResponse r = svc.getCurrentUsage(7L);
        assertThat(r.getPercentUsed()).isEqualTo(0);
        assertThat(r.isAtLimit()).isFalse();
        assertThat(r.getPeriodStart()).isEqualTo(Instant.parse("2026-04-01T00:00:00Z"));
        assertThat(r.getPeriodEnd()).isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));
    }

    @Test
    void halfSpendReportsFiftyPercent() {
        when(repo.totalCostByUserIdInPeriod(eq(7L), any(), any()))
                .thenReturn(new BigDecimal("2.00"));
        UsageResponse r = svc.getCurrentUsage(7L);
        assertThat(r.getPercentUsed()).isEqualTo(50);
        assertThat(r.isAtLimit()).isFalse();
    }

    @Test
    void atOrOverCapReportsAtLimitAndClampsTo100() {
        when(repo.totalCostByUserIdInPeriod(eq(7L), any(), any()))
                .thenReturn(new BigDecimal("4.00"));
        UsageResponse r = svc.getCurrentUsage(7L);
        assertThat(r.getPercentUsed()).isEqualTo(100);
        assertThat(r.isAtLimit()).isTrue();
    }

    @Test
    void overCapClampsToHundredButStaysAtLimit() {
        when(repo.totalCostByUserIdInPeriod(eq(7L), any(), any()))
                .thenReturn(new BigDecimal("12.34"));
        UsageResponse r = svc.getCurrentUsage(7L);
        assertThat(r.getPercentUsed()).isEqualTo(100);
        assertThat(r.isAtLimit()).isTrue();
    }

    @Test
    void unlimitedEmail_reportsZeroAndNotAtLimit_evenWhenSpendIsOverCap() {
        Set<String> exempt = new HashSet<>();
        exempt.add("Admin@Example.com"); // setter normalizes to lowercase
        props.setUnlimitedEmails(exempt);
        User u = User.builder().id(7L).email("admin@example.com").name("Admin").build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(u));

        UsageResponse r = svc.getCurrentUsage(7L);

        assertThat(r.getPercentUsed()).isEqualTo(0);
        assertThat(r.isAtLimit()).isFalse();
        // Repo was never queried for cost — exemption short-circuits.
        verify(repo, never()).totalCostByUserIdInPeriod(anyLong(), any(), any());
    }

    @Test
    void nonUnlimitedEmail_stillSubjectToCap() {
        Set<String> exempt = new HashSet<>();
        exempt.add("admin@example.com");
        props.setUnlimitedEmails(exempt);
        User u = User.builder().id(7L).email("ordinary@example.com").name("Ordinary").build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(u));
        when(repo.totalCostByUserIdInPeriod(eq(7L), any(), any()))
                .thenReturn(new BigDecimal("4.00"));

        UsageResponse r = svc.getCurrentUsage(7L);

        assertThat(r.isAtLimit()).isTrue();
    }

    /* ---------- Item D (§6 A1) — free-tier cap selection ---------- */

    private User freeUser() {
        return User.builder().id(7L).email("free@example.com").name("Free").build();
    }

    private User proUser() {
        return User.builder().id(7L).email("pro@example.com").name("Pro")
                .subscriptionExpiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .build();
    }

    @Test
    void flagOff_freeUser_keepsProCap_andSkipsUserLookup() {
        // Exact pre-Phase-D behavior: no allowlist + flag off ⇒ the user row is
        // never even read, and the 400¢ cap applies to everyone.
        when(repo.totalCostByUserIdInPeriod(eq(7L), any(), any()))
                .thenReturn(new BigDecimal("1.50"));

        UsageResponse r = svc.getCurrentUsage(7L);

        assertThat(r.isAtLimit()).isFalse();
        assertThat(r.getPercentUsed()).isEqualTo(37); // 1.50 / 4.00, floored
        verify(userRepository, never()).findById(anyLong());
    }

    @Test
    void freeTierA1_freeUser_cappedAtFreeLimit() {
        subscriptionProps.setFreeAnalysisTier("a1");
        when(userRepository.findById(7L)).thenReturn(Optional.of(freeUser()));
        when(repo.totalCostByUserIdInPeriod(eq(7L), any(), any()))
                .thenReturn(new BigDecimal("1.50"));

        UsageResponse r = svc.getCurrentUsage(7L);

        assertThat(r.isAtLimit()).isTrue();
        assertThat(r.getPercentUsed()).isEqualTo(100);
    }

    @Test
    void freeTierA1_freeUser_percentIsAgainstFreeLimit() {
        subscriptionProps.setFreeAnalysisTier("a1");
        when(userRepository.findById(7L)).thenReturn(Optional.of(freeUser()));
        when(repo.totalCostByUserIdInPeriod(eq(7L), any(), any()))
                .thenReturn(new BigDecimal("0.75"));

        UsageResponse r = svc.getCurrentUsage(7L);

        assertThat(r.isAtLimit()).isFalse();
        assertThat(r.getPercentUsed()).isEqualTo(50); // 0.75 / 1.50
    }

    @Test
    void freeTierA1_proUser_keepsProCap() {
        subscriptionProps.setFreeAnalysisTier("a1");
        when(userRepository.findById(7L)).thenReturn(Optional.of(proUser()));
        when(repo.totalCostByUserIdInPeriod(eq(7L), any(), any()))
                .thenReturn(new BigDecimal("1.50"));

        UsageResponse r = svc.getCurrentUsage(7L);

        assertThat(r.isAtLimit()).isFalse();
        assertThat(r.getPercentUsed()).isEqualTo(37); // 1.50 / 4.00 — Pro unaffected
    }

    @Test
    void freeTierA1_unknownUser_failsClosedToFreeLimit() {
        subscriptionProps.setFreeAnalysisTier("a1");
        when(userRepository.findById(7L)).thenReturn(Optional.empty());
        when(repo.totalCostByUserIdInPeriod(eq(7L), any(), any()))
                .thenReturn(new BigDecimal("1.50"));

        UsageResponse r = svc.getCurrentUsage(7L);

        assertThat(r.isAtLimit()).isTrue();
    }

    @Test
    void freeTierA1_unlimitedEmail_stillExempt_evenWhenFree() {
        subscriptionProps.setFreeAnalysisTier("a1");
        props.setUnlimitedEmails(Set.of("free@example.com"));
        when(userRepository.findById(7L)).thenReturn(Optional.of(freeUser()));

        UsageResponse r = svc.getCurrentUsage(7L);

        assertThat(r.isAtLimit()).isFalse();
        assertThat(r.getPercentUsed()).isEqualTo(0);
        verify(repo, never()).totalCostByUserIdInPeriod(anyLong(), any(), any());
    }

    @Test
    void activeSubscriber_isSubjectToCap() {
        // Pro is NOT exempt: the $4/mo cap is a fair-use ceiling that applies
        // to paid users too (margin protection on heavy AI usage). Allowlist is
        // populated so the user IS looked up, but the Pro user isn't on it.
        props.setUnlimitedEmails(Set.of("comp@example.com"));
        User pro = User.builder().id(7L).email("pro@example.com").name("Pro")
                .subscriptionExpiresAt(Instant.parse("2099-01-01T00:00:00Z"))
                .build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(pro));
        when(repo.totalCostByUserIdInPeriod(eq(7L), any(), any()))
                .thenReturn(new BigDecimal("4.00"));

        UsageResponse r = svc.getCurrentUsage(7L);

        assertThat(r.isAtLimit()).isTrue();
        assertThat(r.getPercentUsed()).isEqualTo(100);
    }

    /* ---------- GET /api/usage/breakdown — "analyze my usage" ---------- */

    private Object[] featureRow(String callType, long calls, String costDollars) {
        return new Object[]{callType, calls, new BigDecimal(costDollars)};
    }

    /** Wraps rows as List&lt;Object[]&gt; (List.of(Object[]) would infer List&lt;Object&gt;). */
    private List<Object[]> rows(Object[]... r) {
        return List.of(r);
    }

    @Test
    void breakdown_sumsPerFeature_andReconcilesToSpentCents() {
        // extraction 1.234, synthesis 0.500, chat 0.016  (dollars)
        when(repo.costByCallTypeForUserInPeriod(eq(7L), any(), any()))
                .thenReturn(rows(
                        featureRow("extraction", 3, "1.234"),
                        featureRow("synthesis", 2, "0.500"),
                        featureRow("chat", 5, "0.016")));

        UsageBreakdownResponse r = svc.getUsageBreakdown(7L);

        // Cents rounded HALF_UP per feature: 123, 50, 2. spentCents is the SUM
        // of those rounded per-feature cents (123+50+2 = 175), so the bars always
        // add up to the headline number (the contract's reconciliation invariant).
        assertThat(r.getSpentCents()).isEqualTo(175);
        int perFeatureSum = r.getByFeature().stream().mapToInt(UsageBreakdownResponse.FeatureUsage::getCents).sum();
        assertThat(perFeatureSum).isEqualTo(r.getSpentCents());

        // Sorted cents DESC, with the pinned labels + call counts preserved.
        assertThat(r.getByFeature()).extracting(UsageBreakdownResponse.FeatureUsage::getFeature)
                .containsExactly("extraction", "synthesis", "chat");
        assertThat(r.getByFeature()).extracting(UsageBreakdownResponse.FeatureUsage::getLabel)
                .containsExactly("AI extraction", "Condition synthesis", "Ask AI chat");
        assertThat(r.getByFeature()).extracting(UsageBreakdownResponse.FeatureUsage::getCents)
                .containsExactly(123, 50, 2);
        assertThat(r.getByFeature()).extracting(UsageBreakdownResponse.FeatureUsage::getCalls)
                .containsExactly(3, 2, 5);

        // Pro cap (400¢) + remaining/atLimit derived from spent.
        assertThat(r.getLimitCents()).isEqualTo(400);
        assertThat(r.getRemainingCents()).isEqualTo(225);
        assertThat(r.isAtLimit()).isFalse();
        assertThat(r.isUnlimited()).isFalse();
    }

    @Test
    void breakdown_periodAndResetMatchGetCurrentUsage() {
        when(repo.costByCallTypeForUserInPeriod(eq(7L), any(), any()))
                .thenReturn(Collections.emptyList());
        when(repo.totalCostByUserIdInPeriod(anyLong(), any(), any()))
                .thenReturn(BigDecimal.ZERO);

        UsageResponse current = svc.getCurrentUsage(7L);
        UsageBreakdownResponse breakdown = svc.getUsageBreakdown(7L);

        assertThat(breakdown.getPeriodStart()).isEqualTo(current.getPeriodStart());
        assertThat(breakdown.getPeriodEnd()).isEqualTo(current.getPeriodEnd());
        assertThat(breakdown.getPeriodStart()).isEqualTo(Instant.parse("2026-04-01T00:00:00Z"));
        assertThat(breakdown.getPeriodEnd()).isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));
        // resetAt = next reset = period end (1st of next month).
        assertThat(breakdown.getResetAt()).isEqualTo(current.getPeriodEnd());
    }

    @Test
    void breakdown_emptyLedger_zeroSpentAndEmptyByFeature() {
        when(repo.costByCallTypeForUserInPeriod(eq(7L), any(), any()))
                .thenReturn(Collections.emptyList());

        UsageBreakdownResponse r = svc.getUsageBreakdown(7L);

        assertThat(r.getSpentCents()).isEqualTo(0);
        assertThat(r.getByFeature()).isEmpty();
        assertThat(r.getRemainingCents()).isEqualTo(400);
        assertThat(r.isAtLimit()).isFalse();
        assertThat(r.isUnlimited()).isFalse();
        assertThat(r.getLimitCents()).isEqualTo(400);
    }

    @Test
    void breakdown_dropsZeroCostFeatures() {
        // An all-error callType can group to zero cost — excluded from byFeature.
        when(repo.costByCallTypeForUserInPeriod(eq(7L), any(), any()))
                .thenReturn(rows(
                        featureRow("extraction", 2, "1.00"),
                        featureRow("gap_analysis", 1, "0.00")));

        UsageBreakdownResponse r = svc.getUsageBreakdown(7L);

        assertThat(r.getByFeature()).extracting(UsageBreakdownResponse.FeatureUsage::getFeature)
                .containsExactly("extraction");
        assertThat(r.getSpentCents()).isEqualTo(100);
    }

    @Test
    void breakdown_atLimit_whenSpentMeetsCap() {
        when(repo.costByCallTypeForUserInPeriod(eq(7L), any(), any()))
                .thenReturn(rows(featureRow("extraction", 1, "4.00")));

        UsageBreakdownResponse r = svc.getUsageBreakdown(7L);

        assertThat(r.getSpentCents()).isEqualTo(400);
        assertThat(r.getRemainingCents()).isEqualTo(0);
        assertThat(r.isAtLimit()).isTrue();
    }

    @Test
    void breakdown_overCap_remainingClampsToZero() {
        when(repo.costByCallTypeForUserInPeriod(eq(7L), any(), any()))
                .thenReturn(rows(featureRow("extraction", 1, "9.99")));

        UsageBreakdownResponse r = svc.getUsageBreakdown(7L);

        assertThat(r.getSpentCents()).isEqualTo(999);
        assertThat(r.getRemainingCents()).isEqualTo(0);
        assertThat(r.isAtLimit()).isTrue();
    }

    @Test
    void breakdown_unlimitedEmail_limitMinusOne_unlimitedTrue_remainingZero() {
        props.setUnlimitedEmails(Set.of("admin@example.com"));
        User u = User.builder().id(7L).email("admin@example.com").name("Admin").build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(u));
        // Even a comp account still books spend; the breakdown reports it, but
        // limit/remaining/atLimit reflect the unlimited exemption.
        when(repo.costByCallTypeForUserInPeriod(eq(7L), any(), any()))
                .thenReturn(rows(featureRow("synthesis", 4, "12.34")));

        UsageBreakdownResponse r = svc.getUsageBreakdown(7L);

        assertThat(r.isUnlimited()).isTrue();
        assertThat(r.getLimitCents()).isEqualTo(-1);
        assertThat(r.getRemainingCents()).isEqualTo(0);
        assertThat(r.isAtLimit()).isFalse();
        assertThat(r.getSpentCents()).isEqualTo(1234);
        assertThat(r.getByFeature()).hasSize(1);
    }

    @Test
    void breakdown_freeTierUser_usesFreeLimit() {
        subscriptionProps.setFreeAnalysisTier("a1");
        when(userRepository.findById(7L)).thenReturn(Optional.of(freeUser()));
        when(repo.costByCallTypeForUserInPeriod(eq(7L), any(), any()))
                .thenReturn(rows(featureRow("extraction", 1, "0.75")));

        UsageBreakdownResponse r = svc.getUsageBreakdown(7L);

        assertThat(r.getLimitCents()).isEqualTo(150); // free cap, not 400
        assertThat(r.getSpentCents()).isEqualTo(75);
        assertThat(r.getRemainingCents()).isEqualTo(75);
        assertThat(r.isAtLimit()).isFalse();
    }

    @Test
    void breakdown_scopedToOwnUserId() {
        // The repo query is called with THIS caller's id only; another user's
        // rows never enter the aggregation (WHERE a.userId = :userId).
        when(repo.costByCallTypeForUserInPeriod(eq(7L), any(), any()))
                .thenReturn(rows(featureRow("extraction", 1, "1.00")));

        svc.getUsageBreakdown(7L);

        verify(repo).costByCallTypeForUserInPeriod(eq(7L), any(), any());
        verify(repo, never()).costByCallTypeForUserInPeriod(eq(99L), any(), any());
    }
}
