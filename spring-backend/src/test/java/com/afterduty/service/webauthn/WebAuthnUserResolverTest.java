package com.afterduty.service.webauthn;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserRecord;
import com.afterduty.model.User;
import com.afterduty.repository.UserRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Resolver tests for {@link WebAuthnUserResolver} (auth program P1.3) — the
 * assert/options identifier → user resolution. Confirms email resolves locally,
 * phone resolves via Firebase, and (crucially for anti-enumeration) an unknown
 * identifier / Firebase failure returns empty WITHOUT throwing.
 */
@Tag("regression")
class WebAuthnUserResolverTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);

    private final WebAuthnUserResolver resolver = new WebAuthnUserResolver(userRepository) {
        @Override
        protected FirebaseAuth firebaseAuth() {
            return firebaseAuth;
        }
    };

    private static User u(long id) {
        return User.builder().id(id).email("v@e.com").name("V").firebaseUid("uid-" + id).build();
    }

    @Test
    void emailResolvesLocallyLowercased() {
        when(userRepository.findByEmail("vet@example.com")).thenReturn(Optional.of(u(1L)));
        assertThat(resolver.resolve("  VET@Example.com ")).isPresent();
    }

    @Test
    void phoneResolvesViaFirebaseThenLocalUid() throws Exception {
        UserRecord rec = mock(UserRecord.class);
        when(rec.getUid()).thenReturn("uid-7");
        when(firebaseAuth.getUserByPhoneNumber("+15551234567")).thenReturn(rec);
        when(userRepository.findByFirebaseUid("uid-7")).thenReturn(Optional.of(u(7L)));

        assertThat(resolver.resolve("+15551234567")).map(User::getId).contains(7L);
    }

    @Test
    void unknownEmailReturnsEmpty() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        assertThat(resolver.resolve("ghost@example.com")).isEmpty();
    }

    @Test
    void unknownPhoneOrFirebaseFailureReturnsEmptyNeverThrows() throws Exception {
        when(firebaseAuth.getUserByPhoneNumber(any())).thenThrow(new RuntimeException("no such user"));
        assertThat(resolver.resolve("+15550000000")).isEmpty();
    }

    @Test
    void blankOrNullIdentifierReturnsEmpty() {
        assertThat(resolver.resolve(null)).isEmpty();
        assertThat(resolver.resolve("  ")).isEmpty();
    }
}
