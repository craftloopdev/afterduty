package com.afterduty.service.webauthn;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.afterduty.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Mints the app session (a Firebase custom token) for a uid whose passkey
 * assertion just verified (auth program P1.3).
 *
 * <p>Deliberately a small, separate minter rather than a change to
 * {@code FirebaseCustomTokenService}: the passkey path already KNOWS the uid (the
 * verified credential belongs to it), so it mints directly for that uid — it must
 * NOT go through the email lookup path. The returned {@code custom_token} has the
 * exact shape the email-code verify returns, so the web driver reuses
 * {@code signInWithCustomTokenAndEstablish} unchanged.
 *
 * <p><b>Rule:</b> a token is minted ONLY for the uid that owns the just-verified
 * credential — never a client-supplied uid.
 */
@Service
public class WebAuthnSessionMinter {

    private static final Logger log = LoggerFactory.getLogger(WebAuthnSessionMinter.class);

    /** Mint a custom token for {@code uid}. Same §6 IAM failure mapping as the email path. */
    public String mintCustomToken(String uid) {
        if (uid == null || uid.isBlank()) {
            // Should never happen (a verified credential always has an owning uid),
            // but never mint for a blank subject.
            throw ApiException.badGateway("Sign-in is temporarily unavailable. Please try again shortly.");
        }
        try {
            return firebaseAuth().createCustomToken(uid);
        } catch (FirebaseAuthException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("signJwt")
                    || msg.contains("PERMISSION_DENIED")
                    || msg.contains("iam.serviceAccounts.signBlob")) {
                log.error("IAM token-signing FAILED while minting a passkey session token. The Cloud Run "
                        + "runtime service account is missing roles/iam.serviceAccountTokenCreator ON ITSELF. "
                        + "Underlying error: {}", msg, e);
                throw ApiException.badGateway(
                        "Sign-in is temporarily unavailable (token signing). Please try again shortly.");
            }
            log.error("Firebase Admin failure minting a passkey session token: {}", msg, e);
            throw ApiException.badGateway("Sign-in is temporarily unavailable. Please try again shortly.");
        }
    }

    /** Seam for tests to inject a mock {@link FirebaseAuth}. */
    protected FirebaseAuth firebaseAuth() {
        return FirebaseAuth.getInstance();
    }
}
