package com.afterduty.service;

import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import com.afterduty.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Wraps the Firebase Admin calls the passwordless email-code flow needs
 * ({@code createCustomToken} / {@code getUserByEmail} / {@code createUser} /
 * {@code updateUser}) and surfaces the §6 IAM gotcha as a clean, actionable error
 * (passwordless-otp-auth-spec §4 / §6).
 *
 * <p><b>Token-minting rule (security):</b> a custom token is minted ONLY for the
 * uid that owns the just-verified email. We never accept a uid/email from the
 * client and never mint a token for an arbitrary address.
 */
@Service
public class FirebaseCustomTokenService {

    private static final Logger log = LoggerFactory.getLogger(FirebaseCustomTokenService.class);

    /** Result of minting a sign-in token for a just-verified email. */
    public record MintResult(String customToken, boolean isNewUser, boolean hasPhone) {
    }

    /** Result of attaching a verified email to the current uid. */
    public record AttachResult(String email, boolean emailVerified) {
    }

    /**
     * §4.1 — resolve (or create, verified) the Firebase user for the just-verified
     * {@code email}, then mint a custom token for that exact uid.
     *
     * @param email MUST be the address whose code was just consumed in this request
     * @return {@link MintResult} with {@code isNewUser} computed post-verify (never pre-send)
     * @throws ApiException 502 on any Admin failure (the IAM signJwt 403 gets the
     *                      §6 clean message; everything else a generic "could not complete sign-in")
     */
    public MintResult mintForVerifiedEmail(String email) {
        FirebaseAuth auth = firebaseAuth();
        UserRecord rec;
        boolean isNewUser;
        try {
            try {
                rec = auth.getUserByEmail(email);     // returning user
                isNewUser = false;
            } catch (FirebaseAuthException notFound) {
                if (notFound.getAuthErrorCode() != AuthErrorCode.USER_NOT_FOUND) {
                    throw notFound;                   // a real Admin failure, not "absent"
                }
                // Sign-in == sign-up. Possession of the code proves the address, so
                // mark it verified — the ID token minted after signInWithCustomToken
                // then carries email_verified:true (honored by getOrCreateFirebaseUser).
                rec = auth.createUser(new UserRecord.CreateRequest()
                        .setEmail(email)
                        .setEmailVerified(true));
                isNewUser = true;
            }
            String token = auth.createCustomToken(rec.getUid());   // §6 IAM gotcha surfaced below
            boolean hasPhone = rec.getPhoneNumber() != null && !rec.getPhoneNumber().isBlank();
            return new MintResult(token, isNewUser, hasPhone);
        } catch (FirebaseAuthException e) {
            throw translateMintFailure(e);
        }
    }

    /**
     * §4.3 — attach a verified email to {@code currentUid}. The caller MUST have
     * already verified+consumed the ATTACH code. Re-checks ownership AFTER consume
     * (TOCTOU guard) and writes email/emailVerified on the CURRENT uid ONLY.
     *
     * @throws ApiException 409 if the email already belongs to another Firebase user;
     *                      502 on any other Admin failure
     */
    public AttachResult attachEmailToCurrentUser(String currentUid, String email) {
        FirebaseAuth auth = firebaseAuth();
        try {
            // Hijack guard: if the email already belongs to ANOTHER uid → 409.
            try {
                UserRecord owner = auth.getUserByEmail(email);
                if (owner != null && !owner.getUid().equals(currentUid)) {
                    throw ApiException.conflict("That email is already in use by another account.");
                }
            } catch (FirebaseAuthException notFound) {
                if (notFound.getAuthErrorCode() != AuthErrorCode.USER_NOT_FOUND) {
                    throw notFound;   // a real Admin failure
                }
                // No existing owner — safe to set on the current uid.
            }

            // Write on the CURRENT uid ONLY — never another uid.
            UserRecord updated = auth.updateUser(new UserRecord.UpdateRequest(currentUid)
                    .setEmail(email)
                    .setEmailVerified(true));
            return new AttachResult(updated.getEmail(), updated.isEmailVerified());
        } catch (FirebaseAuthException e) {
            // A unique-index-style violation (EMAIL_ALREADY_EXISTS) is a hijack
            // race loser → 409, never a 500.
            if (e.getAuthErrorCode() == AuthErrorCode.EMAIL_ALREADY_EXISTS) {
                throw ApiException.conflict("That email is already in use by another account.");
            }
            throw translateMintFailure(e);
        }
    }

    /**
     * Map an Admin failure to a clean client error. The §6 IAM signJwt 403
     * (missing {@code roles/iam.serviceAccountTokenCreator} self-binding on the
     * Cloud Run runtime SA) is logged LOUDLY and actionably, then returned as a
     * generic 502 — the raw IAM error is never sent to the client.
     */
    private ApiException translateMintFailure(FirebaseAuthException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("signJwt")
                || msg.contains("PERMISSION_DENIED")
                || msg.contains("iam.serviceAccounts.signBlob")) {
            log.error("IAM token-signing FAILED while minting a Firebase custom token. The Cloud Run "
                    + "runtime service account is missing roles/iam.serviceAccountTokenCreator ON ITSELF. "
                    + "Fix (one-time): gcloud iam service-accounts add-iam-policy-binding \"$SA\" "
                    + "--member=\"serviceAccount:$SA\" --role=\"roles/iam.serviceAccountTokenCreator\" "
                    + "(where $SA is the Cloud Run runtime SA). Underlying error: {}", msg, e);
            throw ApiException.badGateway(
                    "Sign-in is temporarily unavailable (token signing). Please try again shortly.");
        }
        log.error("Firebase Admin failure during email-code auth: {}", msg, e);
        // Generic client message — never surface the raw Admin exception text.
        throw ApiException.badGateway("Sign-in is temporarily unavailable. Please try again shortly.");
    }

    /** Seam for tests to inject a mock {@link FirebaseAuth}. */
    protected FirebaseAuth firebaseAuth() {
        return FirebaseAuth.getInstance();
    }
}
