package com.afterduty.service;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.auth.UserRecord;
import com.afterduty.model.User;
import com.afterduty.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class FirebaseAuthService {

    private static final Logger log = LoggerFactory.getLogger(FirebaseAuthService.class);

    private final UserRepository userRepository;

    public FirebaseAuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // VCP-DFLT-01: fail CLOSED. The secure value is the default everywhere; dev mode (the
    // X-User-Email bypass) must be opted into explicitly (application-local.yml / DEV_MODE=true).
    @Value("${va-claim.auth.dev-mode:false}")
    private boolean devMode;

    /**
     * Resolve the current user from Authorization header or dev-mode email header.
     *
     * @param authorizationHeader Bearer token from Authorization header
     * @param devEmail            X-User-Email header for dev mode
     * @return User entity
     */
    public User resolveUser(String authorizationHeader, String devEmail) {
        // Dev mode: accept X-User-Email header
        if (devMode && devEmail != null && !devEmail.isEmpty()) {
            return getOrCreateDevUser(devEmail);
        }

        // Production: verify Firebase token
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new SecurityException("Missing or invalid Authorization header");
        }

        String token = authorizationHeader.substring(7);
        try {
            FirebaseToken decoded = FirebaseAuth.getInstance().verifyIdToken(token);
            return getOrCreateFirebaseUser(decoded);
        } catch (Exception e) {
            // VCP-DFLT-01: NEVER derive identity from an unverified token. The previous dev-mode
            // fallback base64-decoded the JWT payload without checking the signature and trusted
            // its `email` claim — an attacker could forge any identity. It is removed entirely.
            // Local dev authenticates via the X-User-Email header (handled above when devMode is on).
            log.error("Firebase token verification failed", e);
            throw new SecurityException("Invalid Firebase token");
        }
    }

    /**
     * Verify a Firebase ID token and return its uid, or {@code null} if the token
     * is missing/invalid. Never derives identity from an unverified token —
     * verification failure ⇒ null.
     *
     * <p><b>Not for step-up.</b> A bare uid check proves only that the presented
     * token belongs to the account, NOT that a fresh factor was just re-proven —
     * the caller's OWN 1-hour session ID token would pass. The step-up phone path
     * must use {@link #verifyFreshPhoneUid(String, long)} so possession is
     * re-proven within the step-up window.
     */
    public String verifyIdTokenUid(String idToken) {
        if (idToken == null || idToken.isBlank()) return null;
        try {
            FirebaseToken decoded = FirebaseAuth.getInstance().verifyIdToken(idToken.trim());
            return decoded.getUid();
        } catch (Exception e) {
            // Invalid/expired token — no identity. Do NOT log the token.
            log.warn("Firebase ID-token verification failed");
            return null;
        }
    }

    /**
     * Verify a Firebase ID token for the step-up PHONE path (auth program P1.2)
     * and return its uid ONLY when the authentication is FRESH — i.e. the token's
     * {@code auth_time} claim is within {@code maxAuthAgeSec} of now. Returns
     * {@code null} if the token is missing/invalid, has no {@code auth_time}, or
     * the authentication is older than the window.
     *
     * <p><b>Why freshness matters:</b> the endpoint is already Bearer-authed, so a
     * bare uid==firebaseUid check is satisfied by the caller's existing session
     * token — it re-proves nothing. Step-up must prove a factor was re-exercised
     * NOW. {@code auth_time} is the seconds-since-epoch of the most recent sign-in
     * behind this token; requiring it to be recent forces the client to complete a
     * fresh phone re-verification (which reauthenticates and advances
     * {@code auth_time}) rather than replay a stale session token. Verification is
     * server-side (project-id bound via {@code verifyIdToken}); no identity is ever
     * derived from an unverified token.
     */
    public String verifyFreshPhoneUid(String idToken, long maxAuthAgeSec) {
        if (idToken == null || idToken.isBlank()) return null;
        try {
            FirebaseToken decoded = FirebaseAuth.getInstance().verifyIdToken(idToken.trim());
            Object authTime = decoded.getClaims().get("auth_time");
            if (!(authTime instanceof Number authTimeNum)) {
                // No auth_time ⇒ cannot prove freshness ⇒ reject.
                log.warn("Step-up phone ID-token missing auth_time claim");
                return null;
            }
            long authEpochSec = authTimeNum.longValue();
            long ageSec = java.time.Instant.now().getEpochSecond() - authEpochSec;
            // Reject stale authentications (replayed session token) AND tokens whose
            // auth_time is implausibly in the future (clock-skew abuse).
            if (ageSec > maxAuthAgeSec || ageSec < -maxAuthAgeSec) {
                log.warn("Step-up phone ID-token authentication is not fresh (age {}s)", ageSec);
                return null;
            }
            return decoded.getUid();
        } catch (Exception e) {
            // Invalid/expired token — no identity. Do NOT log the token.
            log.warn("Step-up phone ID-token verification failed");
            return null;
        }
    }

    /**
     * The E.164 phone number registered on the Firebase user {@code uid}, or
     * {@code null} if the user has none / cannot be resolved. Used by dual-channel
     * recovery (auth program P1.5) to detect whether an account HAS a phone
     * possession channel — the phone lives ONLY on the Firebase {@code UserRecord},
     * not the app {@code User} row. A lookup failure returns {@code null} (fails
     * safe — recovery treats "unknown phone" as single-channel). Never logs PII.
     */
    public String getPhoneNumberForUid(String uid) {
        if (uid == null || uid.isBlank()) return null;
        try {
            UserRecord rec = FirebaseAuth.getInstance().getUser(uid.trim());
            String phone = rec != null ? rec.getPhoneNumber() : null;
            return phone != null && !phone.isBlank() ? phone : null;
        } catch (Exception e) {
            log.debug("Firebase phone lookup for uid found nothing (swallowed)");
            return null;
        }
    }

    private User getOrCreateDevUser(String email) {
        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) return existing.get();

        User user = User.builder()
                .email(email)
                .name(email.split("@")[0])
                .firebaseUid("dev_" + email)
                .build();
        return userRepository.save(user);
    }

    private User getOrCreateFirebaseUser(FirebaseToken decoded) {
        String uid = decoded.getUid();
        Optional<User> existing = userRepository.findByFirebaseUid(uid);
        if (existing.isPresent()) return existing.get();

        String email = decoded.getEmail() != null ? decoded.getEmail() : uid + "@firebase.local";
        String name = decoded.getName() != null ? decoded.getName() : email.split("@")[0];

        // Check if user exists by email (might have been created in dev mode)
        Optional<User> byEmail = userRepository.findByEmail(email);
        if (byEmail.isPresent()) {
            User user = byEmail.get();
            user.setFirebaseUid(uid);
            return userRepository.save(user);
        }

        User user = User.builder()
                .email(email)
                .name(name)
                .firebaseUid(uid)
                .build();
        return userRepository.save(user);
    }
}
