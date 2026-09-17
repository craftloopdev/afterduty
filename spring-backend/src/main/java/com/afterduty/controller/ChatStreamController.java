package com.afterduty.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.config.SecurityConfig;
import com.afterduty.dto.ChatRequest;
import com.afterduty.dto.MessageResponse;
import com.afterduty.model.Claim;
import com.afterduty.model.IntakeMessage;
import com.afterduty.model.User;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.service.AccessScope;
import com.afterduty.service.ChatAgent;
import com.afterduty.service.ChatService;
import com.afterduty.service.ChatStreamListener;
import com.afterduty.service.ClaimAccess;
import com.afterduty.service.ClaimAccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * SSE chat streaming endpoint (Increment 7 §F.2). Deliberately a SEPARATE controller
 * from the 976-line {@code IntakeController} (off-limits this increment): the legacy
 * {@code POST /api/claim/chat} non-streaming path stays as the fallback, untouched.
 *
 * <p>Wire contract (frozen, §F.1): pre-stream failures (401/402/403) are plain JSON so
 * the BFF can map them; success is {@code 200 text/event-stream} with events
 * {@code ack → status* / delta* → message} on success or {@code … → error} on failure,
 * plus {@code : ping} heartbeat comments every 15 s. Runs the blocking agent on a virtual
 * thread ({@code spring.threads.virtual.enabled=true}) so no WebFlux is needed.
 *
 * <p><b>Gating:</b> unlike the legacy own-claim path in IntakeController (which skips the
 * scope check), this controller asserts {@code CHAT} scope on BOTH the share path and the
 * owner own-claim path, so the Increment-7 owner Pro-gate (§E.3) actually fires here —
 * that is the point of the increment (design §4.3a). Free owner ⇒ 402 before any emitter.
 */
@RestController
@RequestMapping("/api/claim")
public class ChatStreamController {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamController.class);

    /** SSE idle timeout. Generous — tool-heavy turns can run a while; heartbeats keep it alive. */
    private static final long SSE_TIMEOUT_MS = 180_000L;
    private static final long HEARTBEAT_MS = 15_000L;

    private final ChatService chatService;
    private final ChatAgent chatAgent;
    private final ClaimAccessService claimAccessService;
    private final ClaimRepository claimRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Per-instance virtual-thread executors. Virtual threads make the blocking worker cheap. */
    private final java.util.concurrent.ExecutorService workerExecutor =
            Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "chat-sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    @Value("${va-claim.chat.streaming:true}")
    private boolean streamingEnabled;

    public ChatStreamController(ChatService chatService,
                               ChatAgent chatAgent,
                               ClaimAccessService claimAccessService,
                               ClaimRepository claimRepository) {
        this.chatService = chatService;
        this.chatAgent = chatAgent;
        this.claimAccessService = claimAccessService;
        this.claimRepository = claimRepository;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest req, HttpServletRequest request) {
        // Rollback lever: streaming off → 409, the web client falls back to POST /chat (§F.4).
        if (!streamingEnabled) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "streaming_disabled");
        }

        // Resolve access + claim BEFORE creating the emitter, so gating errors
        // (401/402/403) surface as normal JSON statuses the BFF can map (§F.1), not as
        // a mid-stream error event.
        User user = getUser(request);
        Claim claim = resolveClaimForChat(user, request);
        Long claimId = claim.getId();
        Long viewerId = user.getId();

        String content = req.getMessage();
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message_required");
        }

        // prepareTurn persists the veteran message + bills usage in its own transaction;
        // a usage-cap hit throws here (still pre-emitter → JSON 429), so the ack we send
        // below truthfully means "user turn persisted".
        ChatService.TurnContext ctx = chatService.prepareTurn(user, claimId, content);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        ScheduledFuture<?> heartbeat = startHeartbeat(emitter);

        workerExecutor.submit(() -> runTurn(user, claimId, viewerId, ctx, content, emitter, heartbeat));
        return emitter;
    }

    /**
     * The streaming worker (virtual thread). Sends {@code ack}, runs the agent forwarding
     * {@code delta}/{@code status}, persists via {@code completeTurn}, sends {@code message}.
     * On agent failure persists the fallback reply and sends {@code error}. A client
     * disconnect makes emitter sends throw {@link IllegalStateException}; those are swallowed
     * per-send so the turn keeps running and the full reply still persists (the thread
     * refetch on next load shows it — same guarantee as the non-streaming model).
     */
    private void runTurn(User user, Long claimId, Long viewerId, ChatService.TurnContext ctx,
                         String content, SseEmitter emitter, ScheduledFuture<?> heartbeat) {
        try {
            send(emitter, "ack", Map.of("user_message_id", ctx.userMsg().getId()));

            ChatStreamListener listener = new ChatStreamListener() {
                @Override public void onDelta(String text) {
                    send(emitter, "delta", Map.of("text", text));
                }
                @Override public void onStatus(String phase, String toolName) {
                    if (toolName != null) {
                        send(emitter, "status", Map.of("phase", phase, "tool", toolName));
                    } else {
                        send(emitter, "status", Map.of("phase", phase));
                    }
                }
            };

            String replyText;
            boolean agentFailed = false;
            String errorCode = "agent_error";
            try {
                replyText = chatAgent.handleStreaming(content, claimId, viewerId,
                        ctx.userMsg().getId(), listener);
            } catch (ChatAgent.ChatRateLimitedException e) {
                // P1-10 — provider brownout after the agent's bounded retries: classify as
                // the RETRYABLE "rate_limited" on the wire (the UI copy for it existed but
                // was unreachable while every failure collapsed to agent_error).
                log.warn("ChatAgent.handleStreaming rate-limited for claim={} viewer={}; persisting fallback",
                        claimId, viewerId, e);
                replyText = ChatService.RATE_LIMITED_FALLBACK_REPLY;
                agentFailed = true;
                errorCode = "rate_limited";
            } catch (RuntimeException e) {
                log.error("ChatAgent.handleStreaming failed for claim={} viewer={}; persisting fallback",
                        claimId, viewerId, e);
                replyText = ChatService.AGENT_FALLBACK_REPLY;
                agentFailed = true;
            }

            // Persist the full reply (real or fallback) — happens even if the client
            // already disconnected, so the canonical thread is always complete.
            IntakeMessage asstMsg = chatService.completeTurn(user, claimId, ctx, replyText);

            if (agentFailed) {
                send(emitter, "error", Map.of("code", errorCode));
            } else {
                send(emitter, "message", toMessageResponse(asstMsg));
            }
            complete(emitter);
        } catch (RuntimeException e) {
            // Last-ditch: something outside the agent threw (e.g. completeTurn DB error).
            log.error("Chat stream worker failed for claim={} viewer={}", claimId, viewerId, e);
            send(emitter, "error", Map.of("code", "agent_error"));
            complete(emitter);
        } finally {
            heartbeat.cancel(false);
        }
    }

    // -------------------------------------------------------------------------
    // SSE plumbing — every send swallows post-disconnect IllegalStateException
    // -------------------------------------------------------------------------

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(toJson(data), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // Client disconnected (or emitter already completed) — keep the worker running
            // so the reply still persists; nothing left to stream.
            log.debug("SSE send '{}' skipped (client gone?): {}", event, e.getMessage());
        }
    }

    private ScheduledFuture<?> startHeartbeat(SseEmitter emitter) {
        return heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                // Disconnected/completed — the worker's finally will cancel us shortly.
                log.debug("SSE heartbeat skipped: {}", e.getMessage());
            }
        }, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
    }

    private void complete(SseEmitter emitter) {
        try { emitter.complete(); } catch (RuntimeException ignored) { /* already completed */ }
    }

    private String toJson(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            // Should never happen for our small maps; degrade to an error payload.
            return "{\"code\":\"serialization_error\"}";
        }
    }

    // -------------------------------------------------------------------------
    // Access dance — duplicated from IntakeController (off-limits) per §F.2.
    // -------------------------------------------------------------------------

    /**
     * Resolves the target claim and enforces CHAT scope. X-View-As present → strict share
     * scope (viewer 403s); absent → owner own-claim path, BUT (unlike IntakeController) we
     * still assert CHAT so the §E.3 owner Pro-gate fires (402). Auto-creates the owner's
     * claim on the own-claim path, matching the legacy chat behavior.
     */
    private Claim resolveClaimForChat(User user, HttpServletRequest request) {
        Optional<ClaimAccess> viewAs = claimAccessService.resolveIfPresent(user, request);
        if (viewAs.isPresent()) {
            ClaimAccess access = viewAs.get();
            claimAccessService.assertScope(access, AccessScope.CHAT, user);
            return claimRepository.findById(access.claimId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "claim_not_found"));
        }
        // Own-claim path: resolve/auto-create, then enforce CHAT (closes the §4.3a hole).
        Claim claim = getOrCreateActiveClaim(user);
        ClaimAccess ownerAccess = new ClaimAccess(claim.getId(), user.getId(), true, true, true);
        claimAccessService.assertScope(ownerAccess, AccessScope.CHAT, user);
        return claim;
    }

    private Claim getOrCreateActiveClaim(User user) {
        List<Claim> claims = claimRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        if (!claims.isEmpty()) {
            return claims.get(0);
        }
        Claim claim = Claim.builder()
                .userId(user.getId())
                .claimType(Claim.ClaimType.INITIAL)
                .status(Claim.ClaimStatus.DRAFT)
                .build();
        return claimRepository.save(claim);
    }

    private User getUser(HttpServletRequest request) {
        User user = (User) request.getAttribute(SecurityConfig.USER_ATTRIBUTE);
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        return user;
    }

    private MessageResponse toMessageResponse(IntakeMessage m) {
        return MessageResponse.builder()
                .id(m.getId())
                .role(m.getRole())
                .content(m.getContent())
                .extractedData(m.getExtractedData())
                .createdAt(serializeInstant(m.getCreatedAt()))
                .build();
    }

    private String serializeInstant(Instant instant) {
        return instant != null ? instant.toString() : "";
    }
}
