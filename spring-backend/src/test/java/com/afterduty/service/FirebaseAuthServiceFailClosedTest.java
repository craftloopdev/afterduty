package com.afterduty.service;

import com.afterduty.repository.UserRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Pre-public security review — VCP-DFLT-01.
 *
 * <p>With dev mode OFF (the new default everywhere except the {@code local} profile), identity must
 * come <em>only</em> from a signature-verified Firebase token. This guards two things:
 * <ul>
 *   <li>the {@code X-User-Email} header alone must never authenticate, and</li>
 *   <li>the deleted unverified-JWT-decode fallback must never resurface (a forged, unsigned token
 *       carrying an arbitrary {@code email} claim must be rejected, not trusted).</li>
 * </ul>
 */
@Tag("regression")
class FirebaseAuthServiceFailClosedTest {

    private FirebaseAuthService serviceWithDevMode(boolean devMode) {
        FirebaseAuthService svc = new FirebaseAuthService(mock(UserRepository.class));
        ReflectionTestUtils.setField(svc, "devMode", devMode);
        return svc;
    }

    @Test
    void xUserEmailHeaderRejectedWhenDevModeOff() {
        FirebaseAuthService svc = serviceWithDevMode(false);
        assertThrows(SecurityException.class,
                () -> svc.resolveUser(null, "attacker@example.com"));
    }

    @Test
    void forgedUnsignedTokenRejectedWhenDevModeOff() {
        FirebaseAuthService svc = serviceWithDevMode(false);
        String forgedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"email\":\"attacker@example.com\"}".getBytes(StandardCharsets.UTF_8));
        String forged = "eyJhbGciOiJub25lIn0." + forgedPayload + ".";
        assertThrows(SecurityException.class,
                () -> svc.resolveUser("Bearer " + forged, null));
    }
}
