package com.afterduty.service.webauthn;

import com.afterduty.config.AuthProperties;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.RelyingPartyV2;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/**
 * Builds and holds the configured {@link RelyingPartyV2} (auth program P1.3).
 *
 * <p>The RP is the security core: it validates challenge binding + single-use +
 * TTL, exact origin match, rpId hash, the signature, sign-count monotonicity
 * (regression ⇒ reject as a cloned authenticator), and the user-verification
 * flag per policy. All of that is the Yubico library's job — we only wire it with
 * the env-driven identity/origin and our DB-backed credential repository.
 *
 * <p>{@code validateSignatureCounter(true)} is explicit (it is also the default):
 * a non-increasing counter on an authenticator that reports one is rejected.
 */
@Component
@SuppressWarnings("deprecation")   // RelyingPartyV2 / CredentialRepositoryV2 tier — see StoredCredential javadoc.
public class WebAuthnRelyingParty {

    private final RelyingPartyV2<StoredCredential> rp;
    private final AuthProperties authProperties;

    public WebAuthnRelyingParty(AuthProperties authProperties, DbCredentialRepository credentialRepository) {
        this.authProperties = authProperties;
        AuthProperties.Webauthn cfg = authProperties.getWebauthn();

        RelyingPartyIdentity identity = RelyingPartyIdentity.builder()
                .id(cfg.getRpId())
                .name(cfg.getRpName())
                .build();

        this.rp = RelyingParty.builder()
                .identity(identity)
                .credentialRepositoryV2(credentialRepository)
                // Exact-origin match (no subdomain/port widening) — the assertion's
                // origin must equal the configured origin exactly.
                .origins(Set.of(cfg.getOrigin()))
                .allowOriginPort(false)
                .allowOriginSubdomain(false)
                // attestation="none" (contract): we don't collect/verify an
                // attestation trust path, so untrusted attestation is allowed
                // (there is no trust source to check against).
                .allowUntrustedAttestation(true)
                // Reject sign-count regressions (cloned authenticator). Explicit.
                .validateSignatureCounter(true)
                .build();
    }

    public RelyingPartyV2<StoredCredential> rp() {
        return rp;
    }

    public String rpId() {
        return authProperties.getWebauthn().getRpId();
    }

    /**
     * Derive the opaque, stable, non-PII WebAuthn user handle for an app user.
     *
     * <p>WebAuthn requires the user handle to NOT be personally identifying (no
     * email/username). We use the first 16 bytes of {@code SHA-256("vcp-uh:" +
     * userId)} — deterministic (so all of a user's credentials share one handle
     * and re-enrollment is stable), opaque, and reversible only to something that
     * already knows the numeric id. 16 bytes is within the 64-byte WebAuthn limit
     * and ample against collisions.
     */
    public static ByteArray userHandle(Long userId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] full = md.digest(("vcp-uh:" + userId).getBytes(StandardCharsets.UTF_8));
            byte[] handle = new byte[16];
            System.arraycopy(full, 0, handle, 0, 16);
            return new ByteArray(handle);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
