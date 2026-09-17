package com.afterduty.controller;

import com.afterduty.exception.ApiException;
import com.afterduty.model.AuthAuditLog;
import com.afterduty.model.User;
import com.afterduty.service.AuthAuditService;
import com.afterduty.service.DeviceCredentialService;
import com.afterduty.service.StepUpService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Device-bound token endpoints (auth program P1.4), implementing the pinned B2
 * device-bound-token contract.
 *
 * <ul>
 *   <li><b>ENROLL</b> (authed): {@code POST /enroll} — mints a random 256-bit device
 *       secret, stores its HASH, returns the plaintext ONCE. Audits DEVICE_ENROLLED.</li>
 *   <li><b>EXCHANGE</b> (PRE-SESSION; exempt in
 *       {@link com.afterduty.config.SecurityConfig}): {@code POST /exchange} — a
 *       constant-time hash check on the non-revoked row mints the app session (a
 *       Firebase custom token, same shape the email-code / passkey verify returns).
 *       Audits DEVICE_LOGIN success/failure.</li>
 *   <li><b>MANAGE</b> (authed): {@code GET /credentials} lists a user's live
 *       devices; {@code DELETE /credentials/{id}} revokes — STEP-UP GUARDED
 *       (revoking a factor is sensitive). Audits DEVICE_REVOKED.</li>
 * </ul>
 *
 * <p>All device-family audit rows carry {@code channel=biometric}.
 */
@RestController
@RequestMapping("/api/auth/device")
public class DeviceCredentialController {

    private static final Logger log = LoggerFactory.getLogger(DeviceCredentialController.class);

    private final DeviceCredentialService deviceService;
    private final StepUpService stepUpService;
    private final AuthAuditService audit;

    public DeviceCredentialController(DeviceCredentialService deviceService,
                                      StepUpService stepUpService,
                                      AuthAuditService audit) {
        this.deviceService = deviceService;
        this.stepUpService = stepUpService;
        this.audit = audit;
    }

    // ---- ENROLL (authed) -----------------------------------------------------

    @PostMapping("/enroll")
    public Map<String, Object> enroll(@RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest request) {
        User user = AuthController.getCurrentUser(request);
        String deviceName = str(body, "deviceName");
        String platform = str(body, "platform");

        DeviceCredentialService.EnrollResult res = deviceService.enroll(user, deviceName, platform);

        audit.record(AuthAuditLog.EVENT_DEVICE_ENROLLED, AuthAuditLog.CHANNEL_BIOMETRIC,
                AuthAuditLog.OUTCOME_SUCCESS, user.getId(), request,
                AuthAuditService.detail().credentialType("biometric"));

        Map<String, Object> out = new LinkedHashMap<>();
        // The plaintext device secret — returned ONCE, never again.
        out.put("deviceSecret", res.deviceSecret());
        out.put("deviceCredentialId", res.deviceCredentialId());
        return out;
    }

    // ---- EXCHANGE (pre-session) ----------------------------------------------

    @PostMapping("/exchange")
    public Map<String, Object> exchange(@RequestBody(required = false) Map<String, Object> body,
                                        HttpServletRequest request) {
        Long deviceCredentialId = asLong(body, "deviceCredentialId");
        String deviceSecret = str(body, "deviceSecret");

        Optional<DeviceCredentialService.ExchangeResult> result =
                deviceService.exchange(deviceCredentialId, deviceSecret);

        if (result.isEmpty()) {
            // Anti-enumeration: never distinguish "no such id" from "wrong secret"
            // from "revoked" — the client just re-runs OTP + re-enroll.
            audit.record(AuthAuditLog.EVENT_DEVICE_LOGIN, AuthAuditLog.CHANNEL_BIOMETRIC,
                    AuthAuditLog.OUTCOME_FAILURE, null, request,
                    AuthAuditService.detail().credentialType("biometric").reason("verify_failed"));
            throw ApiException.badRequest("Biometric sign-in failed. Please sign in again.");
        }

        DeviceCredentialService.ExchangeResult ex = result.get();

        audit.record(AuthAuditLog.EVENT_DEVICE_LOGIN, AuthAuditLog.CHANNEL_BIOMETRIC,
                AuthAuditLog.OUTCOME_SUCCESS, ex.userId(), request,
                AuthAuditService.detail().credentialType("biometric"));

        Map<String, Object> out = new LinkedHashMap<>();
        // SAME key ("custom_token") the email-code / passkey verify returns, so the
        // native driver reuses signInWithCustomToken unchanged.
        out.put("custom_token", ex.customToken());
        return out;
    }

    // ---- MANAGE: list (authed) -----------------------------------------------

    @GetMapping("/credentials")
    public Map<String, Object> listCredentials(HttpServletRequest request) {
        User user = AuthController.getCurrentUser(request);
        List<Map<String, Object>> creds = new ArrayList<>();
        for (DeviceCredentialService.CredentialSummary c : deviceService.listActive(user.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("deviceName", c.deviceName());
            m.put("platform", c.platform());
            m.put("createdAt", c.createdAt() != null ? c.createdAt().toString() : null);
            m.put("lastUsedAt", c.lastUsedAt() != null ? c.lastUsedAt().toString() : null);
            // NEVER expose the device secret hash.
            creds.add(m);
        }
        return Map.of("credentials", creds);
    }

    // ---- MANAGE: revoke (authed, STEP-UP GUARDED) ----------------------------

    @DeleteMapping("/credentials/{id}")
    public ResponseEntity<Void> revokeCredential(@PathVariable Long id, HttpServletRequest request) {
        User user = AuthController.getCurrentUser(request);
        // STEP-UP GUARDED: revoking a factor is sensitive. Throws
        // StepUpRequiredException (403 {code:step_up_required}) unless a valid
        // X-Step-Up token is present; it is consumed on success.
        stepUpService.requireFresh(user, request);

        Optional<?> revoked = deviceService.revoke(id, user.getId());
        if (revoked.isEmpty()) {
            throw ApiException.badRequest("No such device.");
        }

        audit.record(AuthAuditLog.EVENT_DEVICE_REVOKED, AuthAuditLog.CHANNEL_BIOMETRIC,
                AuthAuditLog.OUTCOME_SUCCESS, user.getId(), request,
                AuthAuditService.detail().credentialType("biometric"));

        return ResponseEntity.noContent().build();
    }

    // ---- helpers -------------------------------------------------------------

    private static String str(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        return v instanceof String s ? s : null;
    }

    /** Accept a numeric id as either a JSON number or a numeric string. */
    private static Long asLong(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
