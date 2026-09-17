package com.afterduty.service.webauthn;

import com.afterduty.model.WebAuthnCredential;
import com.yubico.webauthn.CredentialRecord;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.exception.Base64UrlException;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Adapts a persisted {@link WebAuthnCredential} to the Yubico
 * {@link CredentialRecord} the RP verifier consumes (auth program P1.3).
 *
 * <p>Exposes ONLY public material — credential id, user handle, COSE public key,
 * the last accepted signature counter, and transport hints. The private key never
 * exists on the server; the public key is the sole verification input.
 *
 * <p>{@code @SuppressWarnings("deprecation")}: in webauthn-server-core 2.9.0 the
 * {@code CredentialRecord} / V2 tier carries a forward-looking {@code @Deprecated}
 * (a future major will supersede it), but it is the current, correct,
 * username-decoupled API for a {@code CredentialRepositoryV2}. The V1 alternative
 * couples to username lookup we deliberately don't use. Migrating to the eventual
 * V3 tier is a tracked follow-up.
 */
@SuppressWarnings("deprecation")
final class StoredCredential implements CredentialRecord {

    private final ByteArray credentialId;
    private final ByteArray userHandle;
    private final ByteArray publicKeyCose;
    private final long signatureCount;
    private final Set<AuthenticatorTransport> transports;

    private StoredCredential(ByteArray credentialId, ByteArray userHandle, ByteArray publicKeyCose,
                             long signatureCount, Set<AuthenticatorTransport> transports) {
        this.credentialId = credentialId;
        this.userHandle = userHandle;
        this.publicKeyCose = publicKeyCose;
        this.signatureCount = signatureCount;
        this.transports = transports;
    }

    /** Build from a persisted row (base64url fields decoded; invalid data ⇒ IllegalStateException). */
    static StoredCredential from(WebAuthnCredential row) {
        try {
            Set<AuthenticatorTransport> t = new LinkedHashSet<>();
            if (row.getTransports() != null && !row.getTransports().isBlank()) {
                for (String s : Arrays.asList(row.getTransports().split(","))) {
                    String id = s.trim();
                    if (!id.isEmpty()) {
                        // AuthenticatorTransport.of tolerates unknown ids (custom transport);
                        // never throws for a non-standard value.
                        t.add(AuthenticatorTransport.of(id));
                    }
                }
            }
            return new StoredCredential(
                    ByteArray.fromBase64Url(row.getCredentialId()),
                    ByteArray.fromBase64Url(row.getUserHandle()),
                    ByteArray.fromBase64Url(row.getPublicKeyCose()),
                    row.getSignatureCount(),
                    t);
        } catch (Base64UrlException e) {
            // A stored row that can't be decoded is a data-integrity bug, not a
            // client error — fail loudly rather than silently skip the credential.
            throw new IllegalStateException("Corrupt WebAuthn credential row id=" + row.getId(), e);
        }
    }

    @Override
    public ByteArray getCredentialId() {
        return credentialId;
    }

    @Override
    public ByteArray getUserHandle() {
        return userHandle;
    }

    @Override
    public ByteArray getPublicKeyCose() {
        return publicKeyCose;
    }

    @Override
    public long getSignatureCount() {
        return signatureCount;
    }

    @Override
    public Optional<Set<AuthenticatorTransport>> getTransports() {
        return transports.isEmpty() ? Optional.empty() : Optional.of(transports);
    }

    @Override
    public Optional<Boolean> isBackupEligible() {
        return Optional.empty();
    }

    @Override
    public Optional<Boolean> isBackedUp() {
        return Optional.empty();
    }
}
