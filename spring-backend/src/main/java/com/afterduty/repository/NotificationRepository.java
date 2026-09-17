package com.afterduty.repository;

import com.afterduty.model.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<Notification> findByUserIdAndIsReadFalseOrderByCreatedAtDesc(Long userId);

    // Phase B item B1 — GET /api/notifications. Callers pass a PageRequest sorted
    // createdAt DESC, id DESC (the id tie-break keeps one flip's rows in a stable
    // order: the digest is saved last, so it surfaces first).
    List<Notification> findByUserId(Long userId, Pageable pageable);
    List<Notification> findByUserIdAndIsReadFalse(Long userId, Pageable pageable);

    long countByUserIdAndIsReadFalse(Long userId);

    /** Owner-scoped id lookup for mark-read — a foreign id simply resolves to nothing. */
    List<Notification> findByUserIdAndIdIn(Long userId, Collection<Long> ids);
}
