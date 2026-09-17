package com.afterduty.config;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Free-tier boundary flag (2026-07-01 review §6, Phase D item D).
 *
 * <p>{@code va-claim.subscription.free-analysis-tier} (env {@code FREE_ANALYSIS_TIER}):
 * <ul>
 *   <li>{@code off} (default) — today's behavior: the whole analysis pipeline
 *       (extraction, synthesis, gap analysis) is Pro-only. Free users get
 *       document storage and quick-add, nothing runs.</li>
 *   <li>{@code a1} — the plan doc's Option A1: extraction + synthesis run for
 *       FREE users too (identified conditions, triad, combined-rating estimate),
 *       hard-capped by {@code usage.free-limit-cents}. Gap analysis, what-if
 *       scenarios, Ask AI chat, and re-run priority stay Pro-gated.</li>
 * </ul>
 *
 * <p>Consumed by {@code AnalysisScheduler} (per-stage gate split),
 * {@code UsageService} (free vs Pro spend cap), and {@code IntakeController}
 * (gapAnalysisPending must read FALSE for free claims whose gap stage will
 * never run). Unknown values are treated as {@code off} — fail closed to
 * today's Pro-only behavior.
 *
 * <p>{@code va-claim.subscription.demo-emails} (env {@code SUBSCRIPTION_DEMO_EMAILS},
 * comma-separated, default empty) — the App-Review reviewer-demo allowlist
 * (capacitor-ios-spec §H.7.2), mirroring {@code usage.unlimited-emails}. A
 * matching account computes as Pro at the entitlement evaluation point
 * ({@code SubscriptionAccess#isPro}) — a COMPUTED entitlement, no DB write, no
 * {@code subscription_source} change, instantly revocable by removing the email.
 * Empty by default, so it is inert in production until staffed. Pair any demo
 * email into {@code usage.unlimited-emails} too, so a reviewer never trips the
 * AI spend cap mid-review.
 */
@Configuration
@ConfigurationProperties(prefix = "va-claim.subscription")
public class SubscriptionProperties {

    public static final String FREE_ANALYSIS_TIER_OFF = "off";
    public static final String FREE_ANALYSIS_TIER_A1 = "a1";

    private String freeAnalysisTier = FREE_ANALYSIS_TIER_OFF;

    /** Reviewer-demo allowlist — emails that compute as Pro (see class doc). Normalized lower-case. */
    private Set<String> demoEmails = new HashSet<>();

    public String getFreeAnalysisTier() {
        return freeAnalysisTier;
    }

    public void setFreeAnalysisTier(String v) {
        this.freeAnalysisTier = (v == null || v.isBlank())
                ? FREE_ANALYSIS_TIER_OFF
                : v.trim().toLowerCase();
    }

    /** True when the A1 free tier is live: extraction + synthesis run for free users. */
    public boolean isFreeAnalysisA1() {
        return FREE_ANALYSIS_TIER_A1.equals(freeAnalysisTier);
    }

    public Set<String> getDemoEmails() {
        return demoEmails;
    }

    public void setDemoEmails(Set<String> v) {
        this.demoEmails = v == null
                ? new HashSet<>()
                : v.stream()
                        .filter(s -> s != null && !s.isBlank())
                        .map(s -> s.trim().toLowerCase())
                        .collect(Collectors.toSet());
    }

    /** True when this email is on the reviewer-demo allowlist (case-insensitive). */
    public boolean isDemoEmail(String email) {
        return email != null && demoEmails.contains(email.trim().toLowerCase());
    }
}
