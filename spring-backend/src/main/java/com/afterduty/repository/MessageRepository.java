package com.afterduty.repository;

import com.afterduty.model.IntakeMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<IntakeMessage, Long> {
    List<IntakeMessage> findByClaimIdOrderByCreatedAt(Long claimId);
    List<IntakeMessage> findByThreadIdOrderByCreatedAt(Long threadId);
}
