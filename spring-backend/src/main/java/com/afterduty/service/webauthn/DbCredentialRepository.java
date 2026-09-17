package com.afterduty.service.webauthn;

import com.afterduty.model.WebAuthnCredential;
import com.afterduty.repository.WebAuthnCredentialRepository;
import com.yubico.webauthn.CredentialRepositoryV2;
import com.yubico.webauthn.ToPublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.ByteArray;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * {@link CredentialRepositoryV2} backed by {@code webauthn_credentials} (auth
 * program P1.3). The Yubico RP verifier consults this to (a) look up the
 * descriptors for a user handle (assertion allowCredentials) and (b) resolve a
 * {@link StoredCredential} by credential id + user handle during verification.
 *
 * <p>All reads are keyed by the opaque base64url {@code userHandle} / {@code
 * credentialId} exactly as the verifier supplies them — no email/phone crosses
 * this boundary.
 */
@Component
@SuppressWarnings("deprecation")   // CredentialRepositoryV2 tier — see StoredCredential javadoc.
public class DbCredentialRepository implements CredentialRepositoryV2<StoredCredential> {

    private final WebAuthnCredentialRepository repo;

    public DbCredentialRepository(WebAuthnCredentialRepository repo) {
        this.repo = repo;
    }

    @Override
    public Set<? extends ToPublicKeyCredentialDescriptor> getCredentialDescriptorsForUserHandle(
            ByteArray userHandle) {
        Set<StoredCredential> out = new LinkedHashSet<>();
        for (WebAuthnCredential row : repo.findByUserHandle(userHandle.getBase64Url())) {
            out.add(StoredCredential.from(row));
        }
        return out;
    }

    @Override
    public Optional<StoredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
        return repo.findByCredentialId(credentialId.getBase64Url())
                // The credential must also belong to the asserting user handle — a
                // valid credential id that maps to a DIFFERENT handle must not verify.
                .filter(row -> row.getUserHandle().equals(userHandle.getBase64Url()))
                .map(StoredCredential::from);
    }

    @Override
    public boolean credentialIdExists(ByteArray credentialId) {
        return repo.existsByCredentialId(credentialId.getBase64Url());
    }
}
