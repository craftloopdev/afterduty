package com.afterduty.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The synthetic-email rule (single-sourced on {@link User}): phone-OTP accounts
 * store {@code "<uid>@firebase.local"} placeholders that must read as ABSENT
 * everywhere a real address matters (API responses, Stripe, mail senders).
 */
class UserTest {

    private static User withEmail(String email) {
        User u = new User();
        u.setEmail(email);
        return u;
    }

    @Test
    void getRealEmail_syntheticPlaceholder_isNull() {
        assertThat(withEmail("lioqGis8VdXs7joakU1K@firebase.local").getRealEmail()).isNull();
    }

    @Test
    void getRealEmail_isCaseInsensitiveOnTheSuffix() {
        assertThat(withEmail("Uid@Firebase.LOCAL").getRealEmail()).isNull();
    }

    @Test
    void getRealEmail_realAddress_passesThroughUntouched() {
        assertThat(withEmail("Vet@Example.com").getRealEmail()).isEqualTo("Vet@Example.com");
    }

    @Test
    void getRealEmail_nullEmail_isNull() {
        assertThat(withEmail(null).getRealEmail()).isNull();
    }
}
