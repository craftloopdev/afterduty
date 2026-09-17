package com.afterduty.controller;

import com.afterduty.exception.ApiException;
import com.afterduty.service.RecoveryService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dual-channel factor-2 RECOVERY endpoints (auth program P1.5 — the pinned C
 * contract). BOTH routes are PRE-SESSION (the veteran can't sign in — that's the
 * whole point) and are exempted from the auth filter in
 * {@link com.afterduty.config.SecurityConfig}.
 *
 * <ul>
 *   <li>{@code POST /api/auth/recovery/start} — public; anti-enumeration: ALWAYS
 *       {@code 200 {ok:true}}. Emails a fresh recovery code to a known
 *       dual-channel account's email; the phone half is driven client-side.</li>
 *   <li>{@code POST /api/auth/recovery/verify} — public; requires a FRESH email
 *       code AND a FRESH phone proof for the same account. On success revokes ALL
 *       passkeys + device credentials and returns a {@code custom_token} (same
 *       shape sign-in returns). Single-channel legacy → {@code 409
 *       {code:"recovery_needs_support"}}; any factor failure → generic 400.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth/recovery")
public class RecoveryController {

    private static final Logger log = LoggerFactory.getLogger(RecoveryController.class);

    private final RecoveryService recoveryService;

    public RecoveryController(RecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    // ---- start (anti-enumeration; always 200) --------------------------------

    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody(required = false) Map<String, Object> body,
                                     HttpServletRequest request) {
        String identifier = trim(str(body, "identifier"));
        // ALWAYS 200 regardless of whether the account exists / is dual-channel —
        // the service swallows every failure so the response reveals nothing.
        recoveryService.start(identifier, clientIp(request), request);
        return Map.of("ok", true);
    }

    // ---- verify (dual-channel proof) -----------------------------------------

    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest request) {
        String email = lower(str(body, "email"));
        String emailCode = trim(str(body, "emailCode"));
        String phoneIdToken = trim(str(body, "phoneIdToken"));
        if (email.isEmpty()) {
            throw ApiException.badRequest("Enter your email.");
        }
        if (emailCode.isEmpty()) {
            throw ApiException.badRequest("Enter the emailed recovery code.");
        }
        if (phoneIdToken.isEmpty()) {
            throw ApiException.badRequest("Confirm your phone to finish recovery.");
        }

        RecoveryService.RecoverResult result =
                recoveryService.verify(email, emailCode, phoneIdToken, request);

        Map<String, Object> out = new LinkedHashMap<>();
        // SAME key ("custom_token") the email-code / passkey / device verify
        // returns, so the web/native drivers reuse signInWithCustomToken unchanged.
        out.put("custom_token", result.customToken());
        // Tell the UI what was cleared so the "set up Face ID again" copy is honest.
        out.put("revokedFactors", List.of("passkey", "biometric"));
        return out;
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

    /**
     * Best-effort client IP for the email-code per-IP cap. Behind GCP's LB,
     * {@code X-Forwarded-For} is {@code <client-supplied…>, <real client>, <GFE>};
     * trust the SECOND-TO-LAST entry (the leftmost is attacker-controlled). Mirrors
     * {@code EmailCodeAuthController.clientIp}.
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
}
