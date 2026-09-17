package com.afterduty.controller;

import com.afterduty.exception.ApiException;
import com.afterduty.model.AuthAuditLog;
import com.afterduty.model.User;
import com.afterduty.model.WebAuthnCredential;
import com.afterduty.repository.UserRepository;
import com.afterduty.repository.WebAuthnCredentialRepository;
import com.afterduty.service.AuthAuditService;
import com.afterduty.service.StepUpService;
import com.afterduty.service.webauthn.WebAuthnService;
import com.afterduty.service.webauthn.WebAuthnSessionMinter;
import com.afterduty.service.webauthn.WebAuthnUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * WebAuthn / passkey endpoints (auth program P1.3), implementing the pinned
 * REGISTER / AUTHENTICATE / MANAGE ceremony contract.
 *
 * <ul>
 *   <li><b>REGISTER</b> (authed): {@code POST /register/options},
 *       {@code POST /register/verify} — enroll a passkey on the current account.</li>
 *   <li><b>AUTHENTICATE</b> (pre-session; exempt in {@link com.afterduty.config.SecurityConfig}):
 *       {@code POST /assert/options}, {@code POST /assert/verify} — mints the app
 *       session (a Firebase custom token, same shape the email-code verify
 *       returns). Anti-enumeration: an unknown identifier still returns valid
 *       options.</li>
 *   <li><b>MANAGE</b> (authed): {@code GET /credentials},
 *       {@code PATCH /credentials/{id}}, {@code DELETE /credentials/{id}} — the
 *       DELETE is STEP-UP GUARDED (deleting a factor is sensitive).</li>
 * </ul>
 *
 * <p>The options steps return the raw WebAuthn JSON the browser hands to
 * {@code navigator.credentials.create()/get()} (as {@code application/json}), so
 * the client passes it straight through.
 */
@RestController
@RequestMapping("/api/auth/webauthn")
public class WebAuthnController {

    private static final Logger log = LoggerFactory.getLogger(WebAuthnController.class);

    private final WebAuthnService webAuthnService;
    private final WebAuthnUserResolver userResolver;
    private final WebAuthnSessionMinter sessionMinter;
    private final WebAuthnCredentialRepository credentialRepo;
    private final UserRepository userRepository;
    private final StepUpService stepUpService;
    private final AuthAuditService audit;

    public WebAuthnController(WebAuthnService webAuthnService,
                              WebAuthnUserResolver userResolver,
                              WebAuthnSessionMinter sessionMinter,
                              WebAuthnCredentialRepository credentialRepo,
                              UserRepository userRepository,
                              StepUpService stepUpService,
                              AuthAuditService audit) {
        this.webAuthnService = webAuthnService;
        this.userResolver = userResolver;
        this.sessionMinter = sessionMinter;
        this.credentialRepo = credentialRepo;
        this.userRepository = userRepository;
        this.stepUpService = stepUpService;
        this.audit = audit;
    }

    // ---- REGISTER (authed) ---------------------------------------------------

    @PostMapping("/register/options")
    public ResponseEntity<String> registerOptions(HttpServletRequest request) {
        User user = AuthController.getCurrentUser(request);
        String optionsJson = webAuthnService.startRegistration(user);
        return json(optionsJson);
    }

    @PostMapping("/register/verify")
    public Map<String, Object> registerVerify(@RequestBody(required = false) Map<String, Object> body,
                                              HttpServletRequest request) {
        User user = AuthController.getCurrentUser(request);
        String credentialJson = credentialJson(body);
        String nickname = str(body, "nickname");

        WebAuthnService.RegisterResult res = webAuthnService.finishRegistration(user, credentialJson, nickname);

        audit.record(AuthAuditLog.EVENT_PASSKEY_REGISTERED, AuthAuditLog.CHANNEL_PASSKEY,
                AuthAuditLog.OUTCOME_SUCCESS, user.getId(), request,
                AuthAuditService.detail().credentialType("passkey"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("credentialId", res.credentialId());
        if (res.nickname() != null) out.put("nickname", res.nickname());
        return out;
    }

    // ---- AUTHENTICATE (pre-session) ------------------------------------------

    @PostMapping("/assert/options")
    public ResponseEntity<String> assertOptions(@RequestBody(required = false) Map<String, Object> body,
                                                HttpServletRequest request) {
        String identifier = str(body, "identifier");
        // Resolve WITHOUT leaking existence: an unknown identifier still gets a
        // valid options object (empty allowCredentials) so timing/shape don't
        // reveal enrollment.
        Optional<User> resolved = userResolver.resolve(identifier);
        String optionsJson = webAuthnService.startAssertion(resolved);
        return json(optionsJson);
    }

    @PostMapping("/assert/verify")
    public Map<String, Object> assertVerify(@RequestBody(required = false) Map<String, Object> body,
                                           HttpServletRequest request) {
        String credentialJson = credentialJson(body);

        Optional<WebAuthnService.AssertResult> result = webAuthnService.finishAssertion(credentialJson);
        if (result.isEmpty()) {
            audit.record(AuthAuditLog.EVENT_PASSKEY_LOGIN, AuthAuditLog.CHANNEL_PASSKEY,
                    AuthAuditLog.OUTCOME_FAILURE, null, request,
                    AuthAuditService.detail().credentialType("passkey").reason("verify_failed"));
            throw ApiException.badRequest("Passkey sign-in failed. Please try again.");
        }

        WebAuthnService.AssertResult ar = result.get();
        User user = userRepository.findById(ar.userId()).orElse(null);
        if (user == null || user.getFirebaseUid() == null || user.getFirebaseUid().isBlank()) {
            audit.record(AuthAuditLog.EVENT_PASSKEY_LOGIN, AuthAuditLog.CHANNEL_PASSKEY,
                    AuthAuditLog.OUTCOME_FAILURE, ar.userId(), request,
                    AuthAuditService.detail().credentialType("passkey").reason("no_uid"));
            throw ApiException.badGateway("Could not complete sign-in.");
        }

        // Mint the session for the credential's owning uid ONLY (never client-supplied).
        String customToken = sessionMinter.mintCustomToken(user.getFirebaseUid());

        audit.record(AuthAuditLog.EVENT_PASSKEY_LOGIN, AuthAuditLog.CHANNEL_PASSKEY,
                AuthAuditLog.OUTCOME_SUCCESS, user.getId(), request,
                AuthAuditService.detail().credentialType("passkey"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("custom_token", customToken);
        out.put("credentialId", ar.credentialId());
        return out;
    }

    // ---- MANAGE (authed) -----------------------------------------------------

    @GetMapping("/credentials")
    public Map<String, Object> listCredentials(HttpServletRequest request) {
        User user = AuthController.getCurrentUser(request);
        List<Map<String, Object>> creds = new ArrayList<>();
        for (WebAuthnCredential c : credentialRepo.findByUserId(user.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("nickname", c.getNickname());
            m.put("createdAt", c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
            m.put("lastUsedAt", c.getLastUsedAt() != null ? c.getLastUsedAt().toString() : null);
            m.put("deviceHint", deviceHint(c));
            // NEVER expose the public key or credential id material.
            creds.add(m);
        }
        return Map.of("credentials", creds);
    }

    @PatchMapping("/credentials/{id}")
    public Map<String, Object> renameCredential(@PathVariable Long id,
                                               @RequestBody(required = false) Map<String, Object> body,
                                               HttpServletRequest request) {
        User user = AuthController.getCurrentUser(request);
        WebAuthnCredential cred = credentialRepo.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> ApiException.badRequest("No such passkey."));

        String nickname = WebAuthnService.cleanNickname(str(body, "nickname"));
        if (nickname == null) {
            throw ApiException.badRequest("Enter a name for this passkey.");
        }
        cred.setNickname(nickname);
        credentialRepo.save(cred);

        return Map.of("id", cred.getId(), "nickname", nickname);
    }

    @DeleteMapping("/credentials/{id}")
    public ResponseEntity<Void> deleteCredential(@PathVariable Long id, HttpServletRequest request) {
        User user = AuthController.getCurrentUser(request);
        // STEP-UP GUARDED: deleting a factor is sensitive. Throws
        // StepUpRequiredException (403 {code:step_up_required}) unless a valid
        // X-Step-Up token is present; it is consumed on success.
        stepUpService.requireFresh(user, request);

        WebAuthnCredential cred = credentialRepo.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> ApiException.badRequest("No such passkey."));
        credentialRepo.delete(cred);

        audit.record(AuthAuditLog.EVENT_PASSKEY_REVOKED, AuthAuditLog.CHANNEL_PASSKEY,
                AuthAuditLog.OUTCOME_SUCCESS, user.getId(), request,
                AuthAuditService.detail().credentialType("passkey"));

        return ResponseEntity.noContent().build();
    }

    // ---- helpers -------------------------------------------------------------

    /**
     * A coarse, non-identifying device hint from transports/aaguid. Never the
     * public key or aaguid raw — just a friendly bucket for the manage UI.
     */
    static String deviceHint(WebAuthnCredential c) {
        String t = c.getTransports();
        if (t != null) {
            if (t.contains("internal")) return "This device";
            if (t.contains("hybrid")) return "Phone or tablet";
            if (t.contains("usb") || t.contains("nfc") || t.contains("ble")) return "Security key";
        }
        return "Passkey";
    }

    /** The attestation/assertion response JSON — accepts either {credential:{...}} or the object itself. */
    private static String credentialJson(Map<String, Object> body) {
        if (body == null) throw ApiException.badRequest("Missing passkey response.");
        Object cred = body.get("credential");
        Object payload = cred != null ? cred : body;
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw ApiException.badRequest("Malformed passkey response.");
        }
    }

    private static ResponseEntity<String> json(String rawJson) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(rawJson);
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        return v instanceof String s ? s : null;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
}
