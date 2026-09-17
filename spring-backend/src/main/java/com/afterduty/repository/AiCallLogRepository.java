package com.afterduty.repository;

import com.afterduty.model.AiCallLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Repository
public interface AiCallLogRepository extends JpaRepository<AiCallLog, Long> {

    List<AiCallLog> findByClaimIdOrderByCreatedAtDesc(Long claimId);

    List<AiCallLog> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("SELECT COALESCE(SUM(a.totalCost), 0) FROM AiCallLog a WHERE a.claimId = :claimId")
    BigDecimal totalCostByClaimId(Long claimId);

    @Query("SELECT COALESCE(SUM(a.totalCost), 0) FROM AiCallLog a WHERE a.userId = :userId")
    BigDecimal totalCostByUserId(Long userId);

    @Query("SELECT COALESCE(SUM(a.totalCost), 0) FROM AiCallLog a " +
           "WHERE a.userId = :userId AND a.createdAt >= :since AND a.createdAt < :until")
    BigDecimal totalCostByUserIdInPeriod(Long userId, Instant since, Instant until);

    @Query("SELECT COALESCE(SUM(a.totalCost), 0) FROM AiCallLog a WHERE a.createdAt >= :since")
    BigDecimal totalCostSince(Instant since);

    /// Per-call-type spend for ONE user within [since, until). Scoped to the
    /// caller's own userId — used by the /api/usage/breakdown endpoint. The cost
    /// is summed from the three per-part cost columns (inputCost + outputCost +
    /// thinkingCost), which equals totalCost per row, so the breakdown reconciles
    /// to the same total the cap uses. Columns: [callType, callCount, cost].
    @Query("SELECT a.callType, COUNT(a), " +
           "COALESCE(SUM(COALESCE(a.inputCost, 0) + COALESCE(a.outputCost, 0) + COALESCE(a.thinkingCost, 0)), 0) " +
           "FROM AiCallLog a " +
           "WHERE a.userId = :userId AND a.createdAt >= :since AND a.createdAt < :until " +
           "GROUP BY a.callType")
    List<Object[]> costByCallTypeForUserInPeriod(Long userId, Instant since, Instant until);

    @Query("SELECT a.modelName, COUNT(a), COALESCE(SUM(a.totalCost), 0), " +
            "COALESCE(SUM(a.inputTokens), 0), COALESCE(SUM(a.outputTokens), 0), " +
            "COALESCE(SUM(a.thinkingTokens), 0) " +
            "FROM AiCallLog a WHERE a.createdAt >= :since GROUP BY a.modelName")
    List<Object[]> costBreakdownByModelSince(Instant since);

    @Query("SELECT FUNCTION('DATE', a.createdAt), a.modelName, COUNT(a), " +
            "COALESCE(SUM(a.totalCost), 0), " +
            "COALESCE(SUM(a.inputTokens), 0), COALESCE(SUM(a.outputTokens), 0), " +
            "COALESCE(SUM(a.thinkingTokens), 0) " +
            "FROM AiCallLog a WHERE a.createdAt >= :since " +
            "GROUP BY FUNCTION('DATE', a.createdAt), a.modelName " +
            "ORDER BY FUNCTION('DATE', a.createdAt) DESC")
    List<Object[]> dailyCostByModelSince(Instant since);

    long countByStatusAndCreatedAtAfter(String status, Instant since);

    /// Per-user × per-call-type spend since a point in time. Columns:
    /// [userId, callType, callCount, totalCost, inputTokens, outputTokens, thinkingTokens]
    @Query("SELECT a.userId, a.callType, COUNT(a), COALESCE(SUM(a.totalCost), 0), " +
            "COALESCE(SUM(a.inputTokens), 0), COALESCE(SUM(a.outputTokens), 0), " +
            "COALESCE(SUM(a.thinkingTokens), 0) " +
            "FROM AiCallLog a WHERE a.createdAt >= :since " +
            "GROUP BY a.userId, a.callType " +
            "ORDER BY a.userId ASC")
    List<Object[]> costByUserAndCallTypeSince(Instant since);
}
