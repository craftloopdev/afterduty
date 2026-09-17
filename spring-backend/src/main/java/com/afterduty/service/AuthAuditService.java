package com.afterduty.service;

import com.afterduty.model.AuthAuditLog;
import com.afterduty.repository.AuthAuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Writes append-only {@link AuthAuditLog} rows for the auth flows (P1.1).
 *
 * <p><b>Security invariants:</b>
 * <ul>
 *   <li><b>No secrets/PHI in {@code detail_json}.</b> The only way to attach
 *       structured context is {@link #detail()} — a typed builder that accepts a
 *       fixed allowlist of non-sensitive keys ({@code reason}, {@code purpose},
 *       {@code credentialType}, {@code factor}, {@code mfaModel}). Any other key
 *       is rejected. There is no free-form map setter, so a caller physically
 *       cannot log a code, token, secret, or health fact.</li>
 *   <li><b>Append-only.</b> {@link AuthAuditLogRepository} exposes only
 *       {@code save} + reads — no update/delete path exists.</li>
 *   <li><b>Never blocks the caller.</b> Audit writes run in their own
 *       {@code REQUIRES_NEW} transaction and a failure is swallowed (logged, not
 *       rethrown): an audit outage must never break sign-in or a step-up.</li>
 * </ul>
 */
@Service
public class AuthAuditService {

    private static final Logger log = LoggerFactory.getLogger(AuthAuditService.class);

    /**
     * The ONLY keys permitted in {@code detail_json}. Every value is a short,
     * non-sensitive enum-like token or model name. Deliberately excludes anything
     * that could carry a code, token, email, phone, name, or clinical content.
     */
    static final Set<String> ALLOWED_DETAIL_KEYS =
            Set.of("reason", "purpose", "credentialType", "factor", "mfaModel");

    private final AuthAuditLogRepository repo;

    public AuthAuditService(AuthAuditLogRepository repo) {
        this.repo = repo;
    }

    // ---- typed, allowlist-only detail builder --------------------------------

    /** Start a {@link DetailBuilder}; the only way to attach structured context. */
    public static DetailBuilder detail() {
        return new DetailBuilder();
    }

    /**
     * Builds the {@code detail_json} payload from an allowlist of non-sensitive
     * keys only. Values are truncated to a safe length as belt-and-braces; there
     * is intentionally no {@code put(String key, ...)} escape hatch.
     */
    public static final class DetailBuilder {
        private static final int MAX_VALUE_LEN = 120;
        private final Map<String, String> values = new LinkedHashMap<>();

        private DetailBuilder() {
        }

        /** Why the event happened (e.g. "wrong_code", "expired", "new_device"). */
        public DetailBuilder reason(String v) {
            return put("reason", v);
        }

        /** The OTP purpose lane (e.g. "signin", "attach", "stepup"). */
        public DetailBuilder purpose(String v) {
            return put("purpose", v);
        }

        /** The credential class (e.g. "otp", "passkey", "biometric"). */
        public DetailBuilder credentialType(String v) {
            return put("credentialType", v);
        }

        /** The step-up factor (e.g. "otp"). */
        public DetailBuilder factor(String v) {
            return put("factor", v);
        }

        /** The active MFA model (ENROLLMENT_DEVICE_TRUST | STRICT). */
        public DetailBuilder mfaModel(String v) {
            return put("mfaModel", v);
        }

        private DetailBuilder put(String key, String value) {
            if (!ALLOWED_DETAIL_KEYS.contains(key)) {
                // Unreachable from the typed methods above; a hard guard against
                // any future accidental non-allowlisted key.
                throw new IllegalArgumentException("detail key not allowlisted: " + key);
            }
            if (value != null && !value.isBlank()) {
                String v = value.strip();
                values.put(key, v.length() > MAX_VALUE_LEN ? v.substring(0, MAX_VALUE_LEN) : v);
            }
            return this;
        }

        boolean isEmpty() {
            return values.isEmpty();
        }

        /** Compact JSON of allowlisted keys only (values JSON-escaped). */
        String toJson() {
            if (values.isEmpty()) return null;
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(e.getKey()).append("\":\"")
                        .append(jsonEscape(e.getValue())).append('"');
            }
            return sb.append('}').toString();
        }

        private static String jsonEscape(String s) {
            StringBuilder out = new StringBuilder(s.length() + 8);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            return out.toString();
        }
    }

    // ---- record (append) -----------------------------------------------------

    /**
     * Append an audit event. Runs in its own transaction ({@code REQUIRES_NEW})
     * and swallows any failure so an audit outage can never break the auth flow
     * that called it.
     *
     * @param userId  nullable — pre-auth events have none
     * @param request nullable — supplies ip + user-agent when available
     * @param detail  nullable — allowlist-only builder; anything else is impossible
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String eventType,
                       String channel,
                       String outcome,
                       Long userId,
                       HttpServletRequest request,
                       DetailBuilder detail) {
        try {
            AuthAuditLog row = new AuthAuditLog();
            row.setEventType(eventType);
            row.setChannel(channel);
            row.setOutcome(outcome);
            row.setUserId(userId);
            if (request != null) {
                row.setIp(clientIp(request));
                row.setUserAgent(truncate(request.getHeader("User-Agent"), 512));
            }
            if (detail != null && !detail.isEmpty()) {
                row.setDetailJson(detail.toJson());
            }
            row.setCreatedAt(Instant.now());
            repo.save(row);
        } catch (RuntimeException e) {
            // Never let an audit failure surface to the auth flow. Log the event
            // type only (no user-facing detail, no secrets).
            log.error("auth_audit_log write failed for event {} (swallowed)", eventType, e);
        }
    }

    /** Convenience overload without a detail payload. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String eventType, String channel, String outcome,
                       Long userId, HttpServletRequest request) {
        record(eventType, channel, outcome, userId, request, null);
    }

    // ---- helpers -------------------------------------------------------------

    /**
     * Best-effort client IP — same rule as {@code EmailCodeAuthController.clientIp}:
     * behind GCP's LB, {@code X-Forwarded-For} is {@code <client-supplied…>,
     * <real client>, <GFE>}, so trust the SECOND-TO-LAST entry; never the leftmost
     * (attacker-controlled).
     */
    static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] raw = xff.split(",");
            java.util.List<String> parts = new java.util.ArrayList<>();
            for (String p : raw) {
                String t = p.trim();
                if (!t.isEmpty()) parts.add(t);
            }
            if (parts.size() >= 2) return truncate(parts.get(parts.size() - 2), 45);
            if (!parts.isEmpty()) return truncate(parts.get(0), 45);
        }
        return truncate(request.getRemoteAddr(), 45);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
