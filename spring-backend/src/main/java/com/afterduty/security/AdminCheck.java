package com.afterduty.security;

import com.afterduty.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single source of truth for which accounts get admin-only features
 * (debug endpoints, AI cost dashboards, etc.). The list is configured via
 * the {@code ADMIN_EMAILS} env var (comma-separated) and mirrored on the
 * frontend through the {@code ADMIN_EMAILS} dart-define.
 */
@Component
public class AdminCheck {

    private final Set<String> emails;

    public AdminCheck(@Value("${va-claim.admin.emails:}") String emailsCsv) {
        if (emailsCsv == null || emailsCsv.isBlank()) {
            this.emails = Collections.emptySet();
        } else {
            this.emails = Arrays.stream(emailsCsv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(String::toLowerCase)
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    public boolean isAdmin(User user) {
        if (user == null) return false;
        if ("admin".equalsIgnoreCase(user.getRole())) return true;
        String email = user.getEmail();
        return email != null && emails.contains(email.toLowerCase());
    }

    /** Exposed for diagnostics — the configured set, never null. */
    public Set<String> adminEmails() {
        return emails;
    }
}
