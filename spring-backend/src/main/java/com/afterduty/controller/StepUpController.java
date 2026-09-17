package com.afterduty.controller;

import com.afterduty.exception.ApiException;
import com.afterduty.model.AuthAuditLog;
import com.afterduty.model.EmailVerificationCode;
import com.afterduty.model.StepUpToken;
import com.afterduty.model.User;
import com.afterduty.service.AuthAuditService;
import com.afterduty.service.EmailCodeService;
import com.afterduty.service.FirebaseAuthService;
import com.afterduty.service.StepUpService;
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
 * Step-up verification endpoint (auth program P1.2). Authenticated (Bearer) — the
 * caller is already signed in; step-up re-proves a fresh factor for a sensitive
 * action.
 *
 * <p><b>Pinned contract:</b>
 * <pre>
 * POST /api/auth/step-up/verify
 *   body { "factor":"otp", "channel":"email", "code":"NNNNNN" }
 *     | { "factor":"otp", "channel":"phone", "idToken":"&lt;firebase-id-token&gt;" }
 *   → 200 { "stepUpToken":"&lt;opaque&gt;", "expiresInSec":300 }
 *   | 400 bad code | 429 rate-limited
 * </pre>
 *
 * <p>The OTP request for the email step-up code is made via the existing
 * {@code POST /api/auth/email-code/request} with {@code purpose:"stepup"}; here we
 * only VERIFY it — fully server-verified, exactly like sign-in. The phone path
 * verifies a fresh Firebase phone-verify ID token and matches its uid to the
 * current user (possession proof). On success a 300s single-use step-up token is
 * minted and returned.
 */
@RestController
@RequestMapping("/api/auth/step-up")
public class StepUpController {

    private static final Logger log = LoggerFactory.getLogger(StepUpController.class);

    private final EmailCodeService emailCodeService;
    private final FirebaseAuthService firebaseAuthService;
    private final StepUpService stepUpService;
    private final AuthAuditService audit;

    public StepUpController(EmailCodeService emailCodeService,
                            FirebaseAuthService firebaseAuthService,
                            StepUpService stepUpService,
                            AuthAuditService audit) {
        this.emailCodeService = emailCodeService;
        this.firebaseAuthService = firebaseAuthService;
        this.stepUpService = stepUpService;
        this.audit = audit;
    }

    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest request) {
        User user = AuthController.getCurrentUser(request);

        String factor = lower(str(body, "factor"));
        String channel = lower(str(body, "channel"));

        // Only OTP exists in Phase 1 (passkey/biometric added later).
        if (!StepUpToken.FACTOR_OTP.equals(factor)) {
            throw ApiException.badRequest("Unsupported step-up factor");
        }

        if (AuthAuditLog.CHANNEL_EMAIL.equals(channel)) {
            verifyEmail(user, body, request);
        } else if (AuthAuditLog.CHANNEL_PHONE.equals(channel)) {
            verifyPhone(user, body, request);
        } else {
            throw ApiException.badRequest("Unsupported step-up channel");
        }

        // Verified → mint a fresh 300s single-use step-up token bound to the user.
        StepUpService.MintResult mint = stepUpService.mint(user.getId(), StepUpToken.FACTOR_OTP);

        audit.record(AuthAuditLog.EVENT_STEP_UP_VERIFIED, channel,
                AuthAuditLog.OUTCOME_SUCCESS, user.getId(), request,
                AuthAuditService.detail().factor(factor).credentialType("otp"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stepUpToken", mint.stepUpToken());
        out.put("expiresInSec", mint.expiresInSec());
        return out;
    }

    // ---- email path (fully server-verified, like sign-in) --------------------

    private void verifyEmail(User user, Map<String, Object> body, HttpServletRequest request) {
        // Step-up email must be the account's own email — never a client-supplied
        // arbitrary address (that would let a signed-in attacker step up with a
        // code sent to their OWN mailbox). We verify against the current user's
        // email in the STEPUP purpose lane.
        String email = user.getEmail();
        String code = trim(str(body, "code"));
        if (email == null || email.isBlank()) {
            throw ApiException.badRequest("No email on file to verify");
        }
        if (code.isEmpty()) {
            throw ApiException.badRequest("Enter the verification code");
        }
        try {
            emailCodeService.verifyAndConsume(email.toLowerCase().trim(), code,
                    EmailVerificationCode.PURPOSE_STEPUP);
        } catch (ApiException e) {
            audit.record(AuthAuditLog.EVENT_STEP_UP_FAILED, AuthAuditLog.CHANNEL_EMAIL,
                    AuthAuditLog.OUTCOME_FAILURE, user.getId(), request,
                    AuthAuditService.detail().factor("otp").reason("bad_code"));
            throw e;   // 400 (bad code) / 429 (already surfaced upstream on request)
        }
    }

    // ---- phone path (verify a fresh Firebase phone-verify ID token) ----------

    private void verifyPhone(User user, Map<String, Object> body, HttpServletRequest request) {
        String idToken = trim(str(body, "idToken"));
        if (idToken.isEmpty()) {
            throw ApiException.badRequest("Missing phone verification token");
        }
        // The token must be valid, belong to the SAME account (possession proof),
        // AND be FRESH — its auth_time must fall within the step-up window so the
        // caller's existing (up-to-1-hour-old) session ID token can't satisfy the
        // challenge. Only a just-completed phone re-verification advances auth_time.
        String uid = firebaseAuthService.verifyFreshPhoneUid(idToken, StepUpService.STEP_UP_TTL_SEC);
        boolean ok = uid != null
                && user.getFirebaseUid() != null
                && uid.equals(user.getFirebaseUid());
        if (!ok) {
            audit.record(AuthAuditLog.EVENT_STEP_UP_FAILED, AuthAuditLog.CHANNEL_PHONE,
                    AuthAuditLog.OUTCOME_FAILURE, user.getId(), request,
                    AuthAuditService.detail().factor("otp").reason("bad_token"));
            throw ApiException.badRequest("Phone verification failed");
        }
    }

    // ---- helpers -------------------------------------------------------------

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
