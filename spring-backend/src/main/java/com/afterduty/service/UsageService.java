package com.afterduty.service;

import com.afterduty.config.SubscriptionProperties;
import com.afterduty.config.UsageProperties;
import com.afterduty.dto.UsageBreakdownResponse;
import com.afterduty.dto.UsageResponse;
import com.afterduty.model.User;
import com.afterduty.repository.AiCallLogRepository;
import com.afterduty.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class UsageService {
    private final AiCallLogRepository repo;
    private final UsageProperties props;
    private final UserRepository userRepository;
    private final Clock clock;
    private final SubscriptionProperties subscriptionProps;

    public UsageService(AiCallLogRepository repo, UsageProperties props,
                        UserRepository userRepository, Clock clock,
                        SubscriptionProperties subscriptionProps) {
        this.repo = repo;
        this.props = props;
        this.userRepository = userRepository;
        this.clock = clock;
        this.subscriptionProps = subscriptionProps;
    }

    public UsageResponse getCurrentUsage(Long userId) {
        Window w = resolveWindow(userId);

        if (w.unlimited) {
            return new UsageResponse(0, false, w.start, w.end);
        }

        BigDecimal spent = repo.totalCostByUserIdInPeriod(userId, w.start, w.end);
        BigDecimal limitDollars = BigDecimal.valueOf(w.limitCents)
                .divide(BigDecimal.valueOf(100));

        int percent;
        if (limitDollars.signum() == 0) {
            percent = 0;
        } else {
            percent = spent.multiply(BigDecimal.valueOf(100))
                    .divide(limitDollars, 0, RoundingMode.FLOOR)
                    .intValue();
            if (percent > 100) percent = 100;
            if (percent < 0)   percent = 0;
        }

        boolean atLimit = spent.compareTo(limitDollars) >= 0;
        return new UsageResponse(percent, atLimit, w.start, w.end);
    }

    /**
     * Per-feature breakdown of the caller's AI spend for the current period
     * ("analyze my usage"). Reuses the EXACT period window + effective-limit +
     * unlimited resolution as {@link #getCurrentUsage} so the two endpoints
     * never disagree. The breakdown is read-only over the AiCallLog ledger and
     * scoped to {@code userId} only.
     *
     * <p>Each feature's cents is the sum of inputCost + outputCost + thinkingCost
     * over that callType's rows (dollars → cents, HALF_UP). spentCents is the
     * total spend rounded once; the sum of byFeature cents equals spentCents by
     * construction (rounding reconciles at the total).
     */
    public UsageBreakdownResponse getUsageBreakdown(Long userId) {
        Window w = resolveWindow(userId);

        List<Object[]> rows = repo.costByCallTypeForUserInPeriod(userId, w.start, w.end);

        // Round each feature to cents, then sum the ROUNDED cents into the total.
        // This is what makes sum(byFeature[].cents) == spentCents exactly (the
        // contract's reconciliation invariant): the headline number is defined
        // as the sum of the bars, never a separately-rounded grand total.
        List<UsageBreakdownResponse.FeatureUsage> features = new ArrayList<>();
        for (Object[] row : rows) {
            String callType = (String) row[0];
            long calls = ((Number) row[1]).longValue();
            BigDecimal cost = row[2] == null ? BigDecimal.ZERO : (BigDecimal) row[2];
            int cents = toCents(cost);
            if (cents <= 0) {
                // Non-zero features only. Sub-half-cent spend (or an all-error
                // callType summing to 0) rounds away and is not shown.
                continue;
            }
            features.add(new UsageBreakdownResponse.FeatureUsage(
                    callType, labelFor(callType), cents, (int) calls));
        }

        // Sort cents DESC; break ties by feature name for a stable order.
        features.sort(Comparator
                .comparingInt(UsageBreakdownResponse.FeatureUsage::getCents).reversed()
                .thenComparing(UsageBreakdownResponse.FeatureUsage::getFeature));

        int spentCents = features.stream()
                .mapToInt(UsageBreakdownResponse.FeatureUsage::getCents).sum();

        if (w.unlimited) {
            return new UsageBreakdownResponse(w.start, w.end, w.end,
                    -1, spentCents, 0, false, true, features);
        }

        int remainingCents = Math.max(0, w.limitCents - spentCents);
        boolean atLimit = spentCents >= w.limitCents;
        return new UsageBreakdownResponse(w.start, w.end, w.end,
                w.limitCents, spentCents, remainingCents, atLimit, false, features);
    }

    /** Dollars → whole cents, rounded HALF_UP. */
    private static int toCents(BigDecimal dollars) {
        return dollars.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    private static final Map<String, String> FEATURE_LABELS = new LinkedHashMap<>(Map.of(
            "extraction", "AI extraction",
            "synthesis", "Condition synthesis",
            "gap_analysis", "Gap analysis",
            "chat", "Ask AI chat"
    ));

    private static String labelFor(String callType) {
        return FEATURE_LABELS.getOrDefault(callType, callType);
    }

    /**
     * Resolves the billing window + effective cap + unlimited flag for a user.
     * This is the single source of truth for the period + limit, shared by
     * getCurrentUsage and getUsageBreakdown so they can never disagree.
     *
     * <p>Only the operator email allowlist (USAGE_UNLIMITED_EMAILS, for comp
     * accounts) is exempt. Pro subscribers are NOT exempt: the monthly cap is a
     * fair-use ceiling on AI cost that applies to paid users too, so one heavy
     * user can't run the plan into the red.
     *
     * <p>Item D (§6 A1) — with va-claim.subscription.free-analysis-tier=a1, free
     * users run extraction + synthesis and their spend books to the same
     * AiCallLog ledger, so they get their own (lower) ceiling:
     * usage.free-limit-cents. With the flag OFF, free users never reach an AI
     * spend path and the user lookup below is skipped exactly as before.
     */
    private Window resolveWindow(Long userId) {
        Instant now = Instant.now(clock);
        ZonedDateTime utcNow = now.atZone(ZoneOffset.UTC);
        Instant start = utcNow.toLocalDate().withDayOfMonth(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = utcNow.toLocalDate().withDayOfMonth(1).plusMonths(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();

        boolean freeTierLive = subscriptionProps != null && subscriptionProps.isFreeAnalysisA1();
        User user = null;
        if (!props.getUnlimitedEmails().isEmpty() || freeTierLive) {
            user = userRepository.findById(userId).orElse(null);
        }
        if (user != null && props.isUnlimitedEmail(user.getEmail())) {
            return new Window(start, end, -1, true);
        }

        int limitCents = props.getLimitCents();
        if (freeTierLive && (user == null || !user.hasActiveSubscription())) {
            // Unknown users are treated as free — the lower cap fails closed.
            limitCents = props.getFreeLimitCents();
        }
        return new Window(start, end, limitCents, false);
    }

    /** Resolved billing window: [start, end), effective cap, and unlimited flag. */
    private record Window(Instant start, Instant end, int limitCents, boolean unlimited) {}
}
