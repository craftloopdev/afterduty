package com.afterduty.controller;

import com.afterduty.config.SecurityConfig;
import com.afterduty.exception.ApiException;
import com.afterduty.model.AuthAuditLog;
import com.afterduty.model.EmailVerificationCode;
import com.afterduty.model.User;
import com.afterduty.repository.UserRepository;
import com.afterduty.service.AuthAuditService;
import com.afterduty.service.EmailCodeService;
import com.afterduty.service.FirebaseCustomTokenService;
import com.afterduty.service.StripeService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Passwordless email-code OTP endpoints (passwordless-otp-auth-spec §2). Kept
 * separate from {@link AuthController} because two of these routes are PUBLIC
 * (pre-auth — exempted in {@link SecurityConfig}) and only {@code /attach} is
 * Bearer-authed.
 *
 * <ul>
 *   <li>{@code POST /api/auth/email-code/request} — public; email a 6-digit code; always 200 for a valid email (anti-enumeration).</li>
 *   <li>{@code POST /api/auth/email-code/verify}  — public; verify + mint a Firebase custom token for the exact verified email.</li>
 *   <li>{@code POST /api/auth/email-code/attach}  — authed; verify + set a verified email on the CURRENT uid.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth/email-code")
public class EmailCodeAuthController {

    private static final Logger log = LoggerFactory.getLogger(EmailCodeAuthController.class);

    private final EmailCodeService emailCodeService;
    private final FirebaseCustomTokenService customTokenService;
    private final UserRepository userRepository;
    private final AuthAuditService auditService;
    private final StripeService stripeService;

    public EmailCodeAuthController(EmailCodeService emailCodeService,
                                   FirebaseCustomTokenService customTokenService,
                                   UserRepository userRepository,
                                   AuthAuditService auditService,
                                   StripeService stripeService) {
        this.emailCodeService = emailCodeService;
        this.customTokenService = customTokenService;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.stripeService = stripeService;
    }

    // ---- §2.1 request --------------------------------------------------------

    @PostMapping("/request")
    public Map<String, Object> request(@RequestBody(required = false) Map<String, Object> body,
                                       HttpServletRequest httpRequest) {
        String email = normalizeEmail(str(body, "email"));

        // §2.3 purpose: optional, defaults to "signin". An UNAUTH request with
        // purpose=attach|stepup is treated as signin (fail safe — never trust a
        // client-asserted purpose to widen scope to an authed lane).
        boolean authed = httpRequest.getAttribute(SecurityConfig.USER_ATTRIBUTE) != null;
        String purpose = str(body, "purpose");
        boolean wantsAttach = "attach".equalsIgnoreCase(purpose) && authed;
        // Step-up (auth program P1.2): only for a signed-in user requesting a
        // fresh factor re-proof, and only for that user's OWN email — a step-up
        // code sent to an arbitrary address would be a signed-in attacker
        // stepping up with a code in their own mailbox.
        boolean wantsStepUp = "stepup".equalsIgnoreCase(purpose) && authed
                && isCurrentUserEmail(httpRequest, email);

        if (wantsAttach) {
            emailCodeService.mintAndSend(email, clientIp(httpRequest),
                    "Verify your email for After Duty",
                    EmailVerificationCode.PURPOSE_ATTACH, true);
        } else if (wantsStepUp) {
            emailCodeService.mintAndSend(email, clientIp(httpRequest),
                    "Your After Duty security code",
                    EmailVerificationCode.PURPOSE_STEPUP, false);
            Long uid = currentUserId(httpRequest);
            auditService.record(AuthAuditLog.EVENT_OTP_REQUESTED, AuthAuditLog.CHANNEL_EMAIL,
                    AuthAuditLog.OUTCOME_SUCCESS, uid, httpRequest,
                    AuthAuditService.detail().purpose("stepup"));
        } else {
            emailCodeService.mintAndSend(email, clientIp(httpRequest),
                    "Your After Duty sign-in code",
                    EmailVerificationCode.PURPOSE_SIGNIN, false);
            auditService.record(AuthAuditLog.EVENT_OTP_REQUESTED, AuthAuditLog.CHANNEL_EMAIL,
                    AuthAuditLog.OUTCOME_SUCCESS, null, httpRequest,
                    AuthAuditService.detail().purpose("signin"));
        }
        return Map.of("ok", true);
    }

    /** True when {@code email} is the signed-in caller's own account email. */
    private boolean isCurrentUserEmail(HttpServletRequest req, String email) {
        Object u = req.getAttribute(SecurityConfig.USER_ATTRIBUTE);
        if (!(u instanceof User user) || user.getEmail() == null) return false;
        return user.getEmail().equalsIgnoreCase(email);
    }

    private Long currentUserId(HttpServletRequest req) {
        Object u = req.getAttribute(SecurityConfig.USER_ATTRIBUTE);
        return u instanceof User user ? user.getId() : null;
    }

    // ---- §2.2 verify ---------------------------------------------------------

    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest httpRequest) {
        String email = lower(str(body, "email"));
        String code = trim(str(body, "code"));
        if (email.isEmpty()) throw ApiException.badRequest("Enter your email");
        if (code.isEmpty()) throw ApiException.badRequest("Enter the code");

        // Generic-400 + attempts + expiry + consume-before-mint. Single-use is a
        // hard invariant: a token can NEVER be minted without a fresh consume in
        // the same request. purpose=SIGNIN so an attach code can't be redeemed here.
        // This is a pre-auth route (no user resolved yet) so audit rows carry a
        // null user_id — anti-enumeration: we never look the user up here.
        try {
            emailCodeService.verifyAndConsume(email, code, EmailVerificationCode.PURPOSE_SIGNIN);
        } catch (ApiException e) {
            auditService.record(AuthAuditLog.EVENT_OTP_FAILED, AuthAuditLog.CHANNEL_EMAIL,
                    AuthAuditLog.OUTCOME_FAILURE, null, httpRequest,
                    AuthAuditService.detail().purpose("signin").reason("bad_code"));
            throw e;
        }
        auditService.record(AuthAuditLog.EVENT_OTP_VERIFIED, AuthAuditLog.CHANNEL_EMAIL,
                AuthAuditLog.OUTCOME_SUCCESS, null, httpRequest,
                AuthAuditService.detail().purpose("signin"));

        FirebaseCustomTokenService.MintResult mint = customTokenService.mintForVerifiedEmail(email);
        auditService.record(AuthAuditLog.EVENT_SIGN_IN, AuthAuditLog.CHANNEL_EMAIL,
                AuthAuditLog.OUTCOME_SUCCESS, null, httpRequest,
                AuthAuditService.detail().credentialType("otp"));

        // Insertion-ordered so the JSON keys read naturally; values include a
        // boolean false (Map.of forbids nulls but these are never null here).
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("custom_token", mint.customToken());
        out.put("isNewUser", mint.isNewUser());
        out.put("hasPhone", mint.hasPhone());
        return out;
    }

    // ---- §2.3 attach (authenticated) ----------------------------------------

    @PostMapping("/attach")
    public Map<String, Object> attach(@RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest httpRequest) {
        User user = AuthController.getCurrentUser(httpRequest);
        String email = normalizeEmail(str(body, "email"));
        String code = trim(str(body, "code"));
        if (code.isEmpty()) throw ApiException.badRequest("Enter the verification code");

        // Verify + CONSUME with purpose=ATTACH (an attach code can't be redeemed as
        // a sign-in code and vice-versa) BEFORE writing the email (single-use).
        try {
            emailCodeService.verifyAndConsume(email, code, EmailVerificationCode.PURPOSE_ATTACH);
        } catch (ApiException e) {
            auditService.record(AuthAuditLog.EVENT_OTP_FAILED, AuthAuditLog.CHANNEL_EMAIL,
                    AuthAuditLog.OUTCOME_FAILURE, user.getId(), httpRequest,
                    AuthAuditService.detail().purpose("attach").reason("bad_code"));
            throw e;
        }

        // Hijack guard + write email/emailVerified on the CURRENT uid ONLY
        // (post-consume re-check inside; EMAIL_ALREADY_EXISTS race → 409).
        String currentUid = user.getFirebaseUid();
        if (currentUid == null || currentUid.isBlank()) {
            throw ApiException.badGateway("Could not complete sign-in: missing Firebase uid");
        }
        FirebaseCustomTokenService.AttachResult res =
                customTokenService.attachEmailToCurrentUser(currentUid, email);

        // Mirror into the app User row for the current user. The User table has no
        // emailVerified column, so we mirror the verified email itself; the
        // Firebase record carries emailVerified=true.
        try {
            user.setEmail(res.email());
            userRepository.save(user);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Unique-index loser on the app User.email column → 409, never 500.
            throw ApiException.conflict("That email is already in use by another account.");
        }

        // A phone-OTP subscriber's Stripe customer was created email-less; now
        // that a real address exists, make them reachable for receipts and
        // dunning. Best-effort inside syncCustomerEmail — never fails the attach.
        stripeService.syncCustomerEmail(user);

        auditService.record(AuthAuditLog.EVENT_ATTACH_CHANNEL, AuthAuditLog.CHANNEL_EMAIL,
                AuthAuditLog.OUTCOME_SUCCESS, user.getId(), httpRequest,
                AuthAuditService.detail().purpose("attach"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "verified");
        out.put("email", res.email());
        out.put("emailVerified", res.emailVerified());
        return out;
    }

    // ---- helpers -------------------------------------------------------------

    /**
     * §2.1 shape check: {@code @} present, a {@code .} in the domain, not starting
     * with {@code @}. Normalizes (trim + lower-case). Mirrors VolunTails'
     * predicate so request and verify agree on what's a valid address.
     */
    static String normalizeEmail(String raw) {
        String email = lower(raw);
        int at = email.indexOf('@');
        boolean valid = at > 0                                   // '@' present, not leading
                && email.indexOf('.', at) > at;                  // a '.' in the domain
        if (!valid) throw ApiException.badRequest("Enter a valid email address");
        return email;
    }

    /**
     * §3.5 — best-effort client IP for the per-IP cap. Behind GCP's LB,
     * {@code X-Forwarded-For} is {@code <client-supplied…>, <real client>, <GFE>};
     * trust the SECOND-TO-LAST entry. The leftmost is attacker-controlled and must
     * never be trusted (it could rotate a fake first hop to defeat the per-IP cap).
     * Single-entry header (local/tests) ⇒ that entry; else the socket peer.
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
            if (parts.size() >= 2) return parts.get(parts.size() - 2);
            if (!parts.isEmpty()) return parts.get(0);
        }
        return request.getRemoteAddr();
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null) return "";
        Object v = body.get(key);
        return v instanceof String s ? s : "";
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String lower(String s) {
        return trim(s).toLowerCase();
    }
}
