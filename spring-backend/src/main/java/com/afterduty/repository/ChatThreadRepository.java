package com.afterduty.repository;

import com.afterduty.model.ChatThread;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChatThreadRepository extends JpaRepository<ChatThread, Long> {

    /**
     * Finds the chat thread for a specific viewer on a specific claim.
     */
    Optional<ChatThread> findByViewerUserIdAndClaimId(Long viewerUserId, Long claimId);
}
