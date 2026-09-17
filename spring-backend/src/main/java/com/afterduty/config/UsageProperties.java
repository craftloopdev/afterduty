package com.afterduty.config;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "usage")
public class UsageProperties {
    /** Hard cap in cents per user per period. */
    private int limitCents = 400;
    /**
     * Free-tier hard cap in cents per user per period (§6 A1). Only consulted
     * when {@code va-claim.subscription.free-analysis-tier=a1}; with the flag
     * off, free users never reach any AI spend path, so {@link #limitCents}
     * remains the sole cap exactly as before.
     */
    private int freeLimitCents = 150;
    /** When false, the guard never blocks (used to ship dark). */
    private boolean enabled = true;
    /** Emails exempt from the cap. UsageService reports 0% / atLimit=false for them. */
    private Set<String> unlimitedEmails = new HashSet<>();

    public int getLimitCents() { return limitCents; }
    public void setLimitCents(int v) { this.limitCents = v; }
    public int getFreeLimitCents() { return freeLimitCents; }
    public void setFreeLimitCents(int v) { this.freeLimitCents = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public Set<String> getUnlimitedEmails() { return unlimitedEmails; }
    public void setUnlimitedEmails(Set<String> v) {
        this.unlimitedEmails = v == null
                ? new HashSet<>()
                : v.stream().map(s -> s.trim().toLowerCase()).collect(Collectors.toSet());
    }

    public boolean isUnlimitedEmail(String email) {
        return email != null && unlimitedEmails.contains(email.trim().toLowerCase());
    }
}
