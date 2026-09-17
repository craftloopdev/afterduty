package com.afterduty.service;

import com.afterduty.exception.ApiException;
import com.afterduty.model.EmailVerificationCode;
import com.afterduty.repository.EmailVerificationCodeRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Passwordless email-code OTP core — the mint+send and verify+consume halves,
 * ported verbatim (security-wise) from VolunTails' {@code _mint_and_send_email_code}
 * and {@code _verify_email_code_row} (passwordless-otp-auth-spec §3.4 / §3.6).
 *
 * <p>Security invariants (the §11 checklist):
 * <ul>
 *   <li>Codes stored as SHA-256 hex only — plaintext never stored, logged, or returned.</li>
 *   <li>Constant-time hash compare ({@link MessageDigest#isEqual}).</li>
 *   <li>10-min TTL; max 5 attempts per live code; one generic 400 for every verify failure.</li>
 *   <li>Single live code per email (prior unconsumed codes burned on each mint).</li>
 *   <li>Consume-before-act: the code is consumed in the same tx before the caller mints a token / writes the email.</li>
 *   <li>Anti-enumeration: always {@code 200} for a valid email on request; no user lookup on the mint path (no timing oracle); no notification-log row.</li>
 *   <li>Resend cooldown ≥30s; per-email ≤5/hr; per-IP ≤20/hr (DB-backed, survives multi-instance).</li>
 * </ul>
 */
@Service
public class EmailCodeService {

    private static final Logger log = LoggerFactory.getLogger(EmailCodeService.class);

    // §3.2 constants — ported verbatim.
    static final int EMAIL_CODE_TTL_MIN = 10;
    static final int EMAIL_CODE_MAX_ATTEMPTS = 5;
    static final int EMAIL_CODE_RESEND_COOLDOWN_SEC = 30;
    static final int EMAIL_CODE_MAX_PER_EMAIL_HOUR = 5;
    static final int EMAIL_CODE_MAX_PER_IP_HOUR = 20;

    private static final String GENERIC_VERIFY_FAILURE = "Incorrect or expired code";
    private static final SecureRandom RNG = new SecureRandom();

    private final EmailVerificationCodeRepository repo;
    private final EmailSender emailSender;

    @PersistenceContext
    private EntityManager entityManager;

    // §3.6/§5.4 dev shortcut: only when dev-mode AND SendGrid unconfigured, the
    // literal 123456 verifies. Inert in prod (dev-mode forbidden under 'cloud').
    @Value("${va-claim.auth.dev-mode:false}")
    private boolean devMode;

    @Value("${va-claim.sendgrid.api-key:}")
    private String sendGridApiKey;

    private volatile Boolean isPostgres;  // detected once, lazily.

    public EmailCodeService(EmailVerificationCodeRepository repo, EmailSender emailSender) {
        this.repo = repo;
        this.emailSender = emailSender;
    }

    // ---- mint + send (ports _mint_and_send_email_code) -----------------------

    /**
     * One critical section per email: cooldown read → cap checks → burn prior live
     * codes → sweep dead rows → store sha256(code) → commit → send. Burns no send
     * on a rate-limit (the {@code @Transactional} commits the row only on the happy
     * path; rate-limit throws roll the tx back). {@code email} MUST already be
     * normalized (trimmed + lower-cased). Never resolves a {@code User} (no timing
     * oracle / no enumeration).
     *
     * @param purpose {@link EmailVerificationCode#PURPOSE_SIGNIN} or {@code PURPOSE_ATTACH}
     */
    @Transactional
    public void mintAndSend(String email, String ip, String subject, String purpose, boolean attachVerify) {
        Instant now = Instant.now();
        Instant hourAgo = now.minus(Duration.ofHours(1));

        // 1. Serialize per email on Postgres so cooldown-read → burn → insert is
        //    atomic (no two-live-codes race). Skipped on H2 (local/tests).
        if (postgres()) {
            entityManager
                    .createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:e))")
                    .setParameter("e", email)
                    .getSingleResult();
        }

        // 2. Resend cooldown: newest row for this email must be ≥30s old.
        var recent = repo.findFirstByEmailOrderByCreatedAtDesc(email);
        if (recent.isPresent()
                && Duration.between(recent.get().getCreatedAt(), now).getSeconds()
                        < EMAIL_CODE_RESEND_COOLDOWN_SEC) {
            throw ApiException.tooManyRequests("Please wait a moment before requesting another code");
        }

        // 3. Per-email hourly cap.
        long perEmail = repo.countByEmailAndCreatedAtGreaterThanEqual(email, hourAgo);
        if (perEmail >= EMAIL_CODE_MAX_PER_EMAIL_HOUR) {
            throw ApiException.tooManyRequests("Too many codes requested. Try again later.");
        }

        // 4. Per-IP hourly cap (DB-backed; defeats spray-across-many-emails
        //    mail-bombing the per-email cap alone can't, and survives Cloud Run
        //    multi-instance).
        if (ip != null && !ip.isBlank()) {
            long perIp = repo.countByRequestIpAndCreatedAtGreaterThanEqual(ip, hourAgo);
            if (perIp >= EMAIL_CODE_MAX_PER_IP_HOUR) {
                throw ApiException.tooManyRequests("Too many codes requested. Try again later.");
            }
        }

        // 5. Single live code per email: burn all prior unconsumed codes so an
        //    attacker can't stack multiple live codes (≤5 guesses / 10 min / email).
        repo.burnUnconsumedForEmail(email, now);

        // 6. Opportunistic sweep of long-dead rows (no scheduler exists).
        repo.deleteExpiredBefore(now.minus(Duration.ofDays(1)));

        // 7. Generate + store sha256(code). Plaintext lives only as a local; it is
        //    never persisted, returned, or logged.
        String code = generateCode();
        EmailVerificationCode row = new EmailVerificationCode();
        row.setEmail(email);
        row.setCodeHash(sha256Hex(code));
        row.setExpiresAt(now.plus(Duration.ofMinutes(EMAIL_CODE_TTL_MIN)));
        row.setAttempts(0);
        row.setRequestIp(ip);
        row.setCreatedAt(now);
        row.setPurpose(purpose);
        repo.saveAndFlush(row);

        // 8. Send — no User lookup. On a non-SENT status, 502, unless the
        //    dev-unconfigured grace applies (§5.4).
        String html = attachVerify ? emailVerifyHtml(code) : emailCodeHtml(code);
        EmailSender.Result result = emailSender.sendHtml(email, subject, html);
        if (result.status() != EmailSender.Status.SENT) {
            boolean devUnconfiguredGrace = devMode && (sendGridApiKey == null || sendGridApiKey.isBlank());
            if (!devUnconfiguredGrace) {
                String reason = result.errorMessage() != null
                        ? result.errorMessage() : "email service unavailable";
                throw ApiException.badGateway(
                        "Email could not be sent (status: " + result.status() + "): " + reason);
            }
        }
    }

    // ---- verify + consume (ports _verify_email_code_row) ---------------------

    /**
     * Load the latest live code for {@code (email, purpose-or-null)}
     * ({@code FOR UPDATE} on Postgres), reject wrong / expired / exhausted with a
     * single generic 400, increment attempts on a wrong guess, and CONSUME the code
     * before returning on success. The caller does whatever the verified email
     * entitles (mint a token / set the email) AFTER this returns. {@code email}
     * MUST already be normalized. Throws {@link ApiException} (400) on any failure
     * — no oracle distinguishes wrong / expired / none / exhausted.
     */
    @Transactional
    public void verifyAndConsume(String email, String code, String purpose) {
        Instant now = Instant.now();

        // Latest unconsumed row for (email, purpose-or-NULL). FOR UPDATE on
        // Postgres so a concurrent verify can't double-spend the same row.
        EmailVerificationCode row = loadLiveForVerify(email, purpose);

        if (row == null || now.isAfter(row.getExpiresAt())
                || row.getAttempts() >= EMAIL_CODE_MAX_ATTEMPTS) {
            throw ApiException.badRequest(GENERIC_VERIFY_FAILURE);
        }

        // Dev shortcut: only when dev-mode AND SendGrid genuinely can't send (so
        // it's inert in prod, where dev-mode is forbidden under 'cloud').
        boolean devOk = devMode
                && (sendGridApiKey == null || sendGridApiKey.isBlank())
                && "123456".equals(code);
        if (devOk) {
            log.warn("DEV_MODE email-verify shortcut used (SendGrid unconfigured)");
        } else {
            // Constant-time compare on the hashes (never String.equals).
            byte[] stored = row.getCodeHash().getBytes(StandardCharsets.UTF_8);
            byte[] given = sha256Hex(code).getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(stored, given)) {
                row.setAttempts(row.getAttempts() + 1);
                repo.saveAndFlush(row);
                throw ApiException.badRequest(GENERIC_VERIFY_FAILURE);
            }
        }

        // Correct: consume BEFORE the caller acts — single-use is a hard invariant.
        row.setConsumedAt(now);
        repo.saveAndFlush(row);
    }

    private EmailVerificationCode loadLiveForVerify(String email, String purpose) {
        if (postgres()) {
            // FOR UPDATE row lock (skip the locked, take the latest live one).
            @SuppressWarnings("unchecked")
            List<EmailVerificationCode> rows = entityManager.createNativeQuery(
                            "SELECT * FROM email_verification_code "
                                    + "WHERE email = :email AND consumed_at IS NULL "
                                    + "AND (purpose = :purpose OR purpose IS NULL) "
                                    + "ORDER BY created_at DESC LIMIT 1 FOR UPDATE",
                            EmailVerificationCode.class)
                    .setParameter("email", email)
                    .setParameter("purpose", purpose)
                    .getResultList();
            return rows.isEmpty() ? null : rows.get(0);
        }
        List<EmailVerificationCode> rows = repo.findLiveForVerify(email, purpose, Limit.of(1));
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---- helpers -------------------------------------------------------------

    /** Secure RNG, 000000–999999, zero-padded — equivalent to {@code secrets.randbelow(1_000_000)}. */
    static String generateCode() {
        return String.format("%06d", RNG.nextInt(1_000_000));
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private boolean postgres() {
        Boolean cached = isPostgres;
        if (cached != null) return cached;
        boolean detected;
        try {
            // Inspect the live JDBC connection's product name — works on any
            // Hibernate/Spring version and matches what the dialect actually is.
            org.hibernate.Session session = entityManager.unwrap(org.hibernate.Session.class);
            detected = session.doReturningWork(conn -> {
                String product = conn.getMetaData().getDatabaseProductName();
                return product != null && product.toLowerCase().contains("postgres");
            });
        } catch (RuntimeException e) {
            detected = false;
        }
        isPostgres = detected;
        return detected;
    }

    // ---- email bodies (port _email_code_html / _email_verify_html) -----------

    static String emailCodeHtml(String code) {
        return baseHtml("Your After Duty sign-in code",
                "Enter this code to finish signing in:", code);
    }

    static String emailVerifyHtml(String code) {
        return baseHtml("Verify your email for After Duty",
                "Enter this code to confirm this email address:", code);
    }

    private static String baseHtml(String heading, String lead, String code) {
        String safe = htmlEscape(code);
        return "<div style=\"font-family:-apple-system,Segoe UI,Roboto,sans-serif;"
                + "max-width:480px;margin:0 auto;color:#1a1a1a\">"
                + "<h2 style=\"color:#1b5e20\">" + heading + "</h2>"
                + "<p>" + lead + "</p>"
                + "<p style=\"font-size:34px;font-weight:700;letter-spacing:6px;"
                + "font-family:SFMono-Regular,Menlo,Consolas,monospace;color:#1a1a1a;"
                + "margin:18px 0\">" + safe + "</p>"
                + "<p style=\"font-size:13px;color:#666\">This code expires in 10 minutes. "
                + "If you didn't request it, you can safely ignore this email.</p>"
                + "<p>Thanks,<br>The After Duty team</p>"
                + "</div>";
    }

    private static String htmlEscape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
