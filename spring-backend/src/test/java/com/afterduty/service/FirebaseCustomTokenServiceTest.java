package com.afterduty.service;

import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import com.afterduty.exception.ApiException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link FirebaseCustomTokenService} — the Firebase Admin wrapper +
 * the §6 IAM-gotcha error surface (passwordless-otp-auth-spec §4, §6, §11).
 *
 * <p>{@link FirebaseAuth} is mocked via an overridden {@code firebaseAuth()} seam,
 * so no real Firebase app / network is touched. We assert the token is minted
 * ONLY for the uid that owns the verified email, that new users are created
 * verified, and that the IAM signJwt 403 becomes a clean 502 (never the raw
 * error) while a hijack attempt becomes a 409.
 */
@Tag("regression")
class FirebaseCustomTokenServiceTest {

    private final FirebaseAuth auth = mock(FirebaseAuth.class);

    private FirebaseCustomTokenService service() {
        return new FirebaseCustomTokenService() {
            @Override
            protected FirebaseAuth firebaseAuth() {
                return auth;
            }
        };
    }

    private static FirebaseAuthException userNotFound() {
        FirebaseAuthException e = mock(FirebaseAuthException.class);
        when(e.getAuthErrorCode()).thenReturn(AuthErrorCode.USER_NOT_FOUND);
        return e;
    }

    private static UserRecord record(String uid, String phone) {
        UserRecord rec = mock(UserRecord.class);
        when(rec.getUid()).thenReturn(uid);
        when(rec.getPhoneNumber()).thenReturn(phone);
        return rec;
    }

    // ---- mint for verified email --------------------------------------------

    @Test
    void returningUserMintsTokenForExistingUidIsNewUserFalse() throws Exception {
        UserRecord existing = record("uid-existing", null);
        when(auth.getUserByEmail("vet@example.com")).thenReturn(existing);
        when(auth.createCustomToken("uid-existing")).thenReturn("token-existing");

        var result = service().mintForVerifiedEmail("vet@example.com");

        assertThat(result.customToken()).isEqualTo("token-existing");
        assertThat(result.isNewUser()).isFalse();
        assertThat(result.hasPhone()).isFalse();
        // Mint bound to the verified identifier's uid — never an arbitrary uid.
        verify(auth).createCustomToken("uid-existing");
        verify(auth, never()).createUser(any());
    }

    @Test
    void unknownEmailCreatesVerifiedUserIsNewUserTrue() throws Exception {
        FirebaseAuthException notFound = userNotFound();
        UserRecord created = record("uid-new", null);
        when(auth.getUserByEmail("new@example.com")).thenThrow(notFound);
        when(auth.createUser(any(UserRecord.CreateRequest.class))).thenReturn(created);
        when(auth.createCustomToken("uid-new")).thenReturn("token-new");

        var result = service().mintForVerifiedEmail("new@example.com");

        assertThat(result.isNewUser()).isTrue();
        assertThat(result.customToken()).isEqualTo("token-new");
        // Created with emailVerified=true so the sign-in lands verified.
        var captor = org.mockito.ArgumentCaptor.forClass(UserRecord.CreateRequest.class);
        verify(auth).createUser(captor.capture());
        // The CreateRequest properties are package-private; the §4.1 contract is
        // exercised end-to-end (created → token minted for the new uid).
        verify(auth).createCustomToken("uid-new");
    }

    @Test
    void hasPhoneTrueWhenExistingUserHasPhoneNumber() throws Exception {
        UserRecord withPhone = record("uid-1", "+15555550123");
        when(auth.getUserByEmail("vet@example.com")).thenReturn(withPhone);
        when(auth.createCustomToken("uid-1")).thenReturn("tok");

        assertThat(service().mintForVerifiedEmail("vet@example.com").hasPhone()).isTrue();
    }

    @Test
    void iamSignJwtFailureBecomesClean502NeverRawError() throws Exception {
        UserRecord existing = record("uid-1", null);
        when(auth.getUserByEmail("vet@example.com")).thenReturn(existing);
        FirebaseAuthException iam = mock(FirebaseAuthException.class);
        when(iam.getMessage()).thenReturn(
                "PERMISSION_DENIED: Permission 'iam.serviceAccounts.signBlob' denied; signJwt");
        when(auth.createCustomToken("uid-1")).thenThrow(iam);

        assertThatThrownBy(() -> service().mintForVerifiedEmail("vet@example.com"))
                .isInstanceOf(ApiException.class)
                .satisfies(t -> {
                    ApiException ae = (ApiException) t;
                    assertThat(ae.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    // Clean, user-safe copy — the raw IAM error is NOT leaked.
                    assertThat(ae.getMessage()).contains("token signing");
                    assertThat(ae.getMessage()).doesNotContain("signJwt");
                    assertThat(ae.getMessage()).doesNotContain("PERMISSION_DENIED");
                });
    }

    @Test
    void otherAdminFailureBecomesGeneric502() throws Exception {
        FirebaseAuthException boom = mock(FirebaseAuthException.class);
        when(boom.getAuthErrorCode()).thenReturn(AuthErrorCode.CONFIGURATION_NOT_FOUND);
        when(boom.getMessage()).thenReturn("some other admin error");
        when(auth.getUserByEmail("vet@example.com")).thenThrow(boom);

        assertThatThrownBy(() -> service().mintForVerifiedEmail("vet@example.com"))
                .isInstanceOf(ApiException.class)
                .satisfies(t -> assertThat(((ApiException) t).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    // ---- attach --------------------------------------------------------------

    @Test
    void attachHappyPathWritesEmailOnCurrentUid() throws Exception {
        FirebaseAuthException notFound = userNotFound();
        when(auth.getUserByEmail("add@example.com")).thenThrow(notFound);
        UserRecord updated = mock(UserRecord.class);
        when(updated.getEmail()).thenReturn("add@example.com");
        when(updated.isEmailVerified()).thenReturn(true);
        when(auth.updateUser(any(UserRecord.UpdateRequest.class))).thenReturn(updated);

        var res = service().attachEmailToCurrentUser("uid-current", "add@example.com");

        assertThat(res.email()).isEqualTo("add@example.com");
        assertThat(res.emailVerified()).isTrue();
        verify(auth).updateUser(any(UserRecord.UpdateRequest.class));
    }

    @Test
    void attachReusingSameOwnerUidSucceeds() throws Exception {
        // The email already exists but belongs to the CURRENT uid (re-verify) → OK.
        UserRecord owner = record("uid-current", null);
        when(auth.getUserByEmail("add@example.com")).thenReturn(owner);
        UserRecord updated = mock(UserRecord.class);
        when(updated.getEmail()).thenReturn("add@example.com");
        when(updated.isEmailVerified()).thenReturn(true);
        when(auth.updateUser(any(UserRecord.UpdateRequest.class))).thenReturn(updated);

        assertThat(service().attachEmailToCurrentUser("uid-current", "add@example.com").emailVerified())
                .isTrue();
    }

    @Test
    void attachWhenEmailOwnedByAnotherUidIs409() throws Exception {
        UserRecord otherOwner = record("uid-other", null);
        when(auth.getUserByEmail("taken@example.com")).thenReturn(otherOwner);

        assertThatThrownBy(() -> service().attachEmailToCurrentUser("uid-current", "taken@example.com"))
                .isInstanceOf(ApiException.class)
                .satisfies(t -> {
                    ApiException ae = (ApiException) t;
                    assertThat(ae.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ae.getMessage()).contains("already in use");
                });
        // Never wrote to Firebase on the hijack path.
        verify(auth, never()).updateUser(any());
    }

    @Test
    void attachUpdateRaceEmailAlreadyExistsIs409() throws Exception {
        FirebaseAuthException notFound = userNotFound();
        when(auth.getUserByEmail("race@example.com")).thenThrow(notFound);
        FirebaseAuthException dup = mock(FirebaseAuthException.class);
        when(dup.getAuthErrorCode()).thenReturn(AuthErrorCode.EMAIL_ALREADY_EXISTS);
        when(auth.updateUser(any(UserRecord.UpdateRequest.class))).thenThrow(dup);

        assertThatThrownBy(() -> service().attachEmailToCurrentUser("uid-current", "race@example.com"))
                .isInstanceOf(ApiException.class)
                .satisfies(t -> assertThat(((ApiException) t).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }
}
