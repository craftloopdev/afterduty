package com.afterduty.controller;

import com.afterduty.config.SecurityConfig;
import com.afterduty.model.User;
import com.afterduty.repository.AiCallLogRepository;
import com.afterduty.repository.UserRepository;
import com.afterduty.security.AdminCheck;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@RestController
@RequestMapping("/api/ai-costs")
public class AiCostController {

    private final AiCallLogRepository aiCallLogRepository;
    private final UserRepository userRepository;
    private final AdminCheck adminCheck;

    public AiCostController(AiCallLogRepository aiCallLogRepository,
                            UserRepository userRepository,
                            AdminCheck adminCheck) {
        this.aiCallLogRepository = aiCallLogRepository;
        this.userRepository = userRepository;
        this.adminCheck = adminCheck;
    }

    private void requireAdmin(HttpServletRequest request) {
        User user = (User) request.getAttribute(SecurityConfig.USER_ATTRIBUTE);
        if (!adminCheck.isAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin_only");
        }
    }

    @GetMapping("/summary")
    public Map<String, Object> costSummary(HttpServletRequest request) {
        requireAdmin(request);
        Instant now = Instant.now();
        Instant last24h = now.minus(1, ChronoUnit.DAYS);
        Instant last7d = now.minus(7, ChronoUnit.DAYS);
        Instant last30d = now.minus(30, ChronoUnit.DAYS);

        BigDecimal cost24h = aiCallLogRepository.totalCostSince(last24h);
        BigDecimal cost7d = aiCallLogRepository.totalCostSince(last7d);
        BigDecimal cost30d = aiCallLogRepository.totalCostSince(last30d);

        long errors24h = aiCallLogRepository.countByStatusAndCreatedAtAfter("error", last24h);

        // Breakdown by model (last 7 days)
        List<Object[]> breakdown = aiCallLogRepository.costBreakdownByModelSince(last7d);
        List<Map<String, Object>> modelBreakdown = new ArrayList<>();
        for (Object[] row : breakdown) {
            modelBreakdown.add(Map.of(
                    "model", row[0],
                    "call_count", row[1],
                    "total_cost", row[2],
                    "input_tokens", row[3],
                    "output_tokens", row[4],
                    "thinking_tokens", row[5]
            ));
        }

        // Daily breakdown by model (last 30 days)
        List<Object[]> daily = aiCallLogRepository.dailyCostByModelSince(last30d);
        List<Map<String, Object>> dailyBreakdown = new ArrayList<>();
        for (Object[] row : daily) {
            dailyBreakdown.add(Map.of(
                    "date", row[0] != null ? row[0].toString() : "",
                    "model", row[1],
                    "call_count", row[2],
                    "total_cost", row[3],
                    "input_tokens", row[4],
                    "output_tokens", row[5],
                    "thinking_tokens", row[6]
            ));
        }

        // Recent calls (last 50)
        var recentCalls = aiCallLogRepository.findAll(
                org.springframework.data.domain.PageRequest.of(0, 50,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"))
        ).getContent();

        List<Map<String, Object>> recentList = new ArrayList<>();
        for (var c : recentCalls) {
            Map<String, Object> callMap = new LinkedHashMap<>();
            callMap.put("id", c.getId());
            callMap.put("call_type", c.getCallType());
            callMap.put("provider", c.getProvider());
            callMap.put("model", c.getModelName());
            callMap.put("input_tokens", c.getInputTokens() != null ? c.getInputTokens() : 0);
            callMap.put("output_tokens", c.getOutputTokens() != null ? c.getOutputTokens() : 0);
            callMap.put("thinking_tokens", c.getThinkingTokens() != null ? c.getThinkingTokens() : 0);
            callMap.put("total_cost", c.getTotalCost() != null ? c.getTotalCost() : BigDecimal.ZERO);
            callMap.put("latency_ms", c.getLatencyMs() != null ? c.getLatencyMs() : 0);
            callMap.put("status", c.getStatus());
            callMap.put("error_message", c.getErrorMessage());
            callMap.put("created_at", c.getCreatedAt().toString());
            recentList.add(callMap);
        }

        return Map.of(
                "cost_24h", cost24h,
                "cost_7d", cost7d,
                "cost_30d", cost30d,
                "errors_24h", errors24h,
                "model_breakdown_7d", modelBreakdown,
                "daily_breakdown", dailyBreakdown,
                "recent_calls", recentList
        );
    }

    /// Per-user × per-function spend rollup. Admin-only.
    /// Query param: {@code days} (default 30, capped at 365).
    /// Shape:
    ///   {
    ///     "since": "2026-03-19T...",
    ///     "users": [
    ///       {
    ///         "user_id": 1,
    ///         "email": "admin@example.com",
    ///         "name": "Admin User",
    ///         "total_cost": 12.345,
    ///         "call_count": 200,
    ///         "by_function": [
    ///           { "call_type": "extraction", "call_count": 120,
    ///             "total_cost": 5.5, "input_tokens": ..., ... },
    ///           ...
    ///         ]
    ///       },
    ///       ...
    ///     ]
    ///   }
    @GetMapping("/by-user")
    public Map<String, Object> costByUser(
            HttpServletRequest request,
            @RequestParam(name = "days", defaultValue = "30") int days) {
        requireAdmin(request);
        int clamped = Math.min(Math.max(days, 1), 365);
        Instant since = Instant.now().minus(clamped, ChronoUnit.DAYS);
        List<Object[]> rows = aiCallLogRepository.costByUserAndCallTypeSince(since);

        // Pivot: userId → aggregated user bucket
        Map<Long, Map<String, Object>> byUser = new LinkedHashMap<>();
        for (Object[] row : rows) {
            Long userId = row[0] == null ? null : ((Number) row[0]).longValue();
            String callType = row[1] == null ? "unknown" : row[1].toString();
            long callCount = ((Number) row[2]).longValue();
            BigDecimal cost = (BigDecimal) row[3];
            long inT = ((Number) row[4]).longValue();
            long outT = ((Number) row[5]).longValue();
            long thinkT = ((Number) row[6]).longValue();

            Map<String, Object> userBucket = byUser.computeIfAbsent(userId, id -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("user_id", id);
                m.put("email", "");
                m.put("name", "");
                m.put("total_cost", BigDecimal.ZERO);
                m.put("call_count", 0L);
                m.put("by_function", new ArrayList<Map<String, Object>>());
                return m;
            });
            Map<String, Object> funcEntry = new LinkedHashMap<>();
            funcEntry.put("call_type", callType);
            funcEntry.put("call_count", callCount);
            funcEntry.put("total_cost", cost);
            funcEntry.put("input_tokens", inT);
            funcEntry.put("output_tokens", outT);
            funcEntry.put("thinking_tokens", thinkT);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list =
                    (List<Map<String, Object>>) userBucket.get("by_function");
            list.add(funcEntry);
            userBucket.put("total_cost",
                    ((BigDecimal) userBucket.get("total_cost")).add(cost));
            userBucket.put("call_count",
                    (Long) userBucket.get("call_count") + callCount);
        }

        // Enrich with email/name
        if (!byUser.isEmpty()) {
            List<Long> ids = new ArrayList<>(byUser.keySet());
            userRepository.findAllById(ids).forEach(u -> {
                Map<String, Object> bucket = byUser.get(u.getId());
                if (bucket != null) {
                    bucket.put("email", u.getEmail() != null ? u.getEmail() : "");
                    bucket.put("name", u.getName() != null ? u.getName() : "");
                }
            });
        }

        // Sort users by total cost desc
        List<Map<String, Object>> users = new ArrayList<>(byUser.values());
        users.sort((a, b) -> ((BigDecimal) b.get("total_cost"))
                .compareTo((BigDecimal) a.get("total_cost")));

        // Sort each user's function buckets by cost desc
        for (Map<String, Object> u : users) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> funcs = (List<Map<String, Object>>) u.get("by_function");
            funcs.sort((a, b) -> ((BigDecimal) b.get("total_cost"))
                    .compareTo((BigDecimal) a.get("total_cost")));
        }

        return Map.of(
                "since", since.toString(),
                "days", clamped,
                "users", users
        );
    }

    @GetMapping("/claim/{claimId}")
    public Map<String, Object> costByClaimId(@PathVariable Long claimId, HttpServletRequest request) {
        requireAdmin(request); // VCP-AUTHZ-04: was missing the admin gate its sibling endpoints have
        BigDecimal total = aiCallLogRepository.totalCostByClaimId(claimId);
        var calls = aiCallLogRepository.findByClaimIdOrderByCreatedAtDesc(claimId);
        return Map.of(
                "claim_id", claimId,
                "total_cost", total,
                "call_count", calls.size(),
                "calls", calls.stream().map(c -> {
                    Map<String, Object> callMap = new LinkedHashMap<>();
                    callMap.put("id", c.getId());
                    callMap.put("call_type", c.getCallType());
                    callMap.put("model", c.getModelName());
                    callMap.put("input_tokens", c.getInputTokens() != null ? c.getInputTokens() : 0);
                    callMap.put("output_tokens", c.getOutputTokens() != null ? c.getOutputTokens() : 0);
                    callMap.put("thinking_tokens", c.getThinkingTokens() != null ? c.getThinkingTokens() : 0);
                    callMap.put("total_cost", c.getTotalCost() != null ? c.getTotalCost() : BigDecimal.ZERO);
                    callMap.put("latency_ms", c.getLatencyMs() != null ? c.getLatencyMs() : 0);
                    callMap.put("status", c.getStatus());
                    callMap.put("created_at", c.getCreatedAt().toString());
                    return callMap;
                }).toList()
        );
    }
}
