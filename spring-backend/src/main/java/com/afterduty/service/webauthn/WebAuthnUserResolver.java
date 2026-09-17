package com.afterduty.service.webauthn;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserRecord;
import com.afterduty.model.User;
import com.afterduty.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Resolves the app {@link User} for a pre-session assert identifier (auth program
 * P1.3): an email (local lookup) or a phone in E.164 (Firebase lookup → uid →
 * local user).
 *
 * <p><b>Anti-enumeration:</b> this returns {@code Optional.empty()} for any
 * unknown identifier WITHOUT signalling why — the caller ({@code WebAuthnService})
 * still issues a valid decoy options object so timing/shape never reveal whether
 * an account (or a passkey) exists. No lookup here throws for an absent record.
 */
@Component
public class WebAuthnUserResolver {

    private static final Logger log = LoggerFactory.getLogger(WebAuthnUserResolver.class);

    private final UserRepository userRepository;

    public WebAuthnUserResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** Resolve the user for an identifier, or empty if none. Never throws for "absent". */
    public Optional<User> resolve(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }
        String id = identifier.trim();
        // Phone (E.164 starts with '+') → Firebase; otherwise treat as email.
        if (id.startsWith("+")) {
            return resolveByPhone(id);
        }
        return userRepository.findByEmail(id.toLowerCase(Locale.ROOT));
    }

    private Optional<User> resolveByPhone(String e164) {
        try {
            UserRecord rec = firebaseAuth().getUserByPhoneNumber(e164);
            if (rec == null) return Optional.empty();
            return userRepository.findByFirebaseUid(rec.getUid());
        } catch (Exception e) {
            // Unknown phone / Admin failure: treat as "no such user" — never leak.
            log.debug("WebAuthn phone resolve found no user (swallowed)");
            return Optional.empty();
        }
    }

    /** Seam for tests to inject a mock {@link FirebaseAuth}. */
    protected FirebaseAuth firebaseAuth() {
        return FirebaseAuth.getInstance();
    }
}
