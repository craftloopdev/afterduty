package com.afterduty.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.Notification;
import com.afterduty.model.User;
import com.afterduty.repository.NotificationRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.afterduty.controller.AuthController.getCurrentUser;

/**
 * Phase B item B1 (P1-8) — the veteran-facing "what changed" feed. Rows are
 * written deterministically at the generation flip
 * ({@link com.afterduty.service.synthesis.ConditionGenerationService}); this
 * controller only reads/marks them, always scoped to the authenticated user
 * (the Bearer principal resolved by the auth filter — a client-supplied id is
 * never trusted).
 */
@RestController
public class NotificationController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NotificationController(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /**
     * GET /api/notifications?limit=50&unreadOnly=false
     * → { "notifications": [...], "unreadCount": n }, ordered createdAt DESC
     * (id DESC tie-break so one flip's digest — saved last — surfaces first).
     */
    @GetMapping("/api/notifications")
    public Map<String, Object> list(@RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
                                    @RequestParam(defaultValue = "false") boolean unreadOnly,
                                    HttpServletRequest request) {
        User user = getCurrentUser(request);
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        Pageable page = PageRequest.of(0, capped,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

        List<Notification> rows = unreadOnly
                ? notificationRepository.findByUserIdAndIsReadFalse(user.getId(), page)
                : notificationRepository.findByUserId(user.getId(), page);

        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Notification n : rows) out.add(toRow(n));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("notifications", out);
        body.put("unreadCount", notificationRepository.countByUserIdAndIsReadFalse(user.getId()));
        return body;
    }

    /** Body: { "ids": [n, ...] } or { "all": true } → { "updated": n }. */
    @PostMapping("/api/notifications/mark-read")
    public Map<String, Object> markRead(@RequestBody(required = false) MarkReadRequest req,
                                        HttpServletRequest request) {
        User user = getCurrentUser(request);

        List<Notification> targets;
        if (req != null && Boolean.TRUE.equals(req.all())) {
            targets = notificationRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(user.getId());
        } else if (req != null && req.ids() != null && !req.ids().isEmpty()) {
            // Owner-scoped lookup: ids belonging to another user resolve to nothing.
            targets = notificationRepository.findByUserIdAndIdIn(user.getId(), req.ids());
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide ids or all=true");
        }

        List<Notification> flipped = new ArrayList<>();
        for (Notification n : targets) {
            if (!Boolean.TRUE.equals(n.getIsRead())) {
                n.setIsRead(true);
                flipped.add(n);
            }
        }
        if (!flipped.isEmpty()) notificationRepository.saveAll(flipped);
        return Map.of("updated", flipped.size());
    }

    public record MarkReadRequest(List<Long> ids, Boolean all) {
    }

    private Map<String, Object> toRow(Notification n) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", n.getId());
        row.put("claimId", n.getClaimId());
        row.put("eventType", n.getEventType());
        row.put("title", n.getTitle());
        row.put("body", n.getBody());
        row.put("severity", n.getSeverity());
        row.put("conditionId", n.getConditionId());
        row.put("metadata", parseMetadata(n.getMetadataJson()));
        row.put("isRead", Boolean.TRUE.equals(n.getIsRead()));
        row.put("createdAt", n.getCreatedAt() != null ? n.getCreatedAt().toString() : null);
        return row;
    }

    /** Parsed metadataJson; null when absent or unparseable (never a 500 over one bad row). */
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }
}
