package com.afterduty.service;

import com.afterduty.model.Claim;
import com.afterduty.model.IntakeMessage;
import com.afterduty.model.User;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.MessageRepository;
import com.afterduty.repository.UserRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * TA — Verifies that IntakeMessage.threadId is NOT NULL at the DB level.
 * Saving a message without threadId must throw a constraint violation.
 */
@DataJpaTest
@Tag("regression")
class IntakeMessageThreadIdTest {

    @Autowired
    MessageRepository messageRepository;
    @Autowired
    ClaimRepository claimRepository;
    @Autowired
    UserRepository userRepository;

    @Test
    void savingWithoutThreadId_throwsConstraintViolation() {
        User user = userRepository.save(User.builder()
                .email("ta-user@test.com").name("Test").build());
        Claim claim = claimRepository.save(Claim.builder().userId(user.getId()).build());

        // No threadId set — should violate NOT NULL constraint
        IntakeMessage msg = IntakeMessage.builder()
                .claimId(claim.getId())
                .role("veteran")
                .content("hello")
                .build();

        assertThrows(DataAccessException.class,
                () -> messageRepository.saveAndFlush(msg));
    }
}
