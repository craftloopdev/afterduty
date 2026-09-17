package com.afterduty.repository;

import com.afterduty.model.AuthAuditLog;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * Append-only repository for {@link AuthAuditLog} (auth program P1.1).
 *
 * <p><b>Deliberately NOT a {@code JpaRepository}/{@code CrudRepository}.</b> It
 * extends the bare {@link Repository} marker and declares ONLY {@code save} plus
 * read methods — there is no {@code delete*}, no {@code deleteAll}, and no update
 * path. This makes the append-only contract a compile-time property: no code in
 * the app can delete or mutate an audit row through this repository. (Production
 * additionally REVOKEs UPDATE/DELETE on the table for the app DB role.)
 */
public interface AuthAuditLogRepository extends Repository<AuthAuditLog, Long> {

    /** Append a new event. The only write method — no update/delete exists. */
    AuthAuditLog save(AuthAuditLog event);

    /** Per-user audit trail, newest first (recovery review, support). */
    List<AuthAuditLog> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** Event-type window scan (abuse detection, e.g. STEP_UP_FAILED spikes). */
    List<AuthAuditLog> findByEventTypeOrderByCreatedAtDesc(String eventType);

    /** Total row count (used by tests to assert an append happened). */
    long count();
}
