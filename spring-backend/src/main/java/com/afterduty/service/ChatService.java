package com.afterduty.service;

import com.afterduty.model.Claim;
import com.afterduty.model.ChatThread;
import com.afterduty.model.IntakeMessage;
import com.afterduty.model.User;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ChatThreadRepository;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Encapsulates the full send-message flow for chat:
 * - Thread auto-create or reuse for (viewer, claim)
 * - Usage billing via UsageGuard before the AI call
 * - Persisting veteran and assistant messages with threadId
 * - Setting synthesisNeeded=true when the agent creates new atoms
 *
 * Also provides listMessages scoped to the viewer's thread.
 */
@Service
@Transactional
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatAgent chatAgent;
    private final ChatThreadRepository chatThreadRepository;
    private final MessageRepository messageRepository;
    private final AtomRepository atomRepository;
    private final ClaimRepository claimRepository;
    private final UsageGuard usageGuard;
    private final ClaimAccessService claimAccessService;

    public ChatService(ChatAgent chatAgent,
                       ChatThreadRepository chatThreadRepository,
                       MessageRepository messageRepository,
                       AtomRepository atomRepository,
                       ClaimRepository claimRepository,
                       UsageGuard usageGuard,
                       ClaimAccessService claimAccessService) {
        this.chatAgent = chatAgent;
        this.chatThreadRepository = chatThreadRepository;
        this.messageRepository = messageRepository;
        this.atomRepository = atomRepository;
        this.claimRepository = claimRepository;
        this.usageGuard = usageGuard;
        this.claimAccessService = claimAccessService;
    }

    /**
     * The fallback assistant reply persisted when the agent throws — preserves the
     * veteran's message as history rather than rolling back the whole turn.
     */
    public static final String AGENT_FALLBACK_REPLY =
            "Sorry, the AI is temporarily unavailable. Please try again in a moment.";

    /**
     * P1-10 — capacity-specific fallback persisted when the agent's bounded retry loop
     * exhausted against provider 429/503 brownouts ({@link ChatAgent.ChatRateLimitedException}).
     * Distinct from {@link #AGENT_FALLBACK_REPLY} so the transcript (and the SSE error
     * classification) tell the veteran this is a busy signal, not a failure.
     */
    public static final String RATE_LIMITED_FALLBACK_REPLY =
            "The AI is at capacity right now. Your message is saved — please try again in a minute or two.";

    /**
     * Carries the state captured before the agent runs, so the streaming controller
     * (§F.2) and the non-streaming {@link #sendMessage} can share the exact same
     * prepare/complete bookends around the agent call (Increment 7 §E.4).
     */
    public record TurnContext(ChatThread thread, IntakeMessage userMsg, long atomsBefore) {}

    /**
     * Step 1 of a chat turn (Increment 7 §E.4): resolve/create the thread, bill the
     * viewer's usage cap, persist the veteran message, and snapshot the live atom count.
     * Runs in its own transaction so the user message is durably committed before the
     * (slow, non-transactional) agent call — the streaming path needs the persisted
     * {@code userMsg.id} for the {@code ack} event the instant the agent starts.
     */
    @Transactional
    public TurnContext prepareTurn(User viewer, Long claimId, String content) {
        // Increment 7 §E.3 owner Pro-gate — applied HERE so BOTH chat entry points
        // gate identically: the streaming ChatStreamController (asserts CHAT up front)
        // AND the legacy POST /api/claim/chat whose IntakeController own-claim path
        // skips assertScope. Without this, a free owner chats for free by flipping
        // CHAT_STREAMING=false (web falls back to the legacy POST), by any pre-delta
        // SSE failure triggering the same fallback, or by a direct curl of /chat
        // (acceptance §H.4-6). Owner == claim.user_id; the viewer/VSO path is gated
        // upstream by both controllers' assertScope(CHAT) and is left untouched here.
        // Runs first so a denied free owner never persists a message or burns quota.
        // Honors the va-claim.chat.require-pro rollback flag via the shared guard.
        claimRepository.findById(claimId).ifPresent(claim -> {
            if (claim.getUserId() != null && claim.getUserId().equals(viewer.getId())) {
                claimAccessService.assertOwnerChatAllowed(viewer);
            }
        });

        // Resolve or auto-create the ChatThread for (viewer.id, claimId). Race-safe:
        // two concurrent first messages both see "not found" and both insert; the DB
        // UNIQUE constraint (uq_chat_threads_viewer_claim) lets one win and the loser
        // re-queries the winner's row instead of propagating a 500.
        ChatThread thread = resolveOrCreateThread(viewer.getId(), claimId);

        // Bill the viewer BEFORE the AI call so a cap hit doesn't leave an orphan
        // veteran message or burn quota on failed AI work.
        usageGuard.assertCapacity(viewer.getId());

        // Persist the veteran turn with threadId.
        IntakeMessage userMsg = messageRepository.save(
                IntakeMessage.builder()
                        .claimId(claimId)
                        .threadId(thread.getId())
                        .role("veteran")
                        .content(content)
                        .build());

        // Snapshot LIVE atom count before the agent call to detect new atoms.
        // Mission 5a: both snapshots count non-superseded atoms so the "new atoms?"
        // delta is unaffected by a concurrent extraction supersede.
        long atomsBefore = atomRepository.countByClaimIdAndSupersededByIsNull(claimId);

        return new TurnContext(thread, userMsg, atomsBefore);
    }

    /**
     * Step 3 of a chat turn (Increment 7 §E.4): persist the assistant reply (real or
     * fallback) and flip {@code synthesisNeeded} when the agent created new atoms. Its
     * own transaction so the streaming controller can call it after the agent completes
     * (or fails) — even on client disconnect, so the full reply still persists (§F.2).
     */
    @Transactional
    public IntakeMessage completeTurn(User viewer, Long claimId, TurnContext ctx, String replyText) {
        IntakeMessage asstMsg = messageRepository.save(
                IntakeMessage.builder()
                        .claimId(claimId)
                        .threadId(ctx.thread().getId())
                        .role("assistant")
                        .content(replyText)
                        .build());

        long atomsAfter = atomRepository.countByClaimIdAndSupersededByIsNull(claimId);
        if (atomsAfter > ctx.atomsBefore()) {
            Claim claim = claimRepository.findById(claimId).orElseThrow();
            claim.setSynthesisNeeded(true);
            claimRepository.save(claim);
        }
        return asstMsg;
    }

    /**
     * Send a message on behalf of viewer for the given claim — the NON-streaming path,
     * behavior unchanged. Composes the three §E.4 steps: prepareTurn → agent.handle
     * (with the fallback-on-failure catch that preserves the veteran message) →
     * completeTurn. Returns a map with "veteran_message" and "assistant_message" keys.
     */
    public Map<String, IntakeMessage> sendMessage(User viewer, Long claimId, String content) {
        TurnContext ctx = prepareTurn(viewer, claimId, content);

        // Invoke the agent. If it fails (LLM down, transient 5xx, missing api-key in
        // dev), preserve the user's typed message as history and surface a gentle
        // fallback reply rather than rolling back the whole transaction.
        String replyText;
        try {
            replyText = chatAgent.handle(content, claimId, viewer.getId(), ctx.userMsg().getId());
        } catch (ChatAgent.ChatRateLimitedException e) {
            // P1-10 — provider brownout after bounded retries: persist the capacity-specific
            // copy so the veteran is told to retry, not that something broke.
            log.warn("ChatAgent.handle rate-limited for thread={} viewer={} claim={}; saving rate-limited fallback",
                    ctx.thread().getId(), viewer.getId(), claimId, e);
            replyText = RATE_LIMITED_FALLBACK_REPLY;
        } catch (RuntimeException e) {
            log.error("ChatAgent.handle failed for thread={} viewer={} claim={}; saving fallback reply",
                    ctx.thread().getId(), viewer.getId(), claimId, e);
            replyText = AGENT_FALLBACK_REPLY;
        }

        IntakeMessage asstMsg = completeTurn(viewer, claimId, ctx, replyText);
        return Map.of("veteran_message", ctx.userMsg(), "assistant_message", asstMsg);
    }

    /**
     * Get or create the chat thread for a given (viewer, claim) pair, safely
     * handling the race where two concurrent first messages both try to insert.
     */
    private ChatThread resolveOrCreateThread(Long viewerUserId, Long claimId) {
        return chatThreadRepository
                .findByViewerUserIdAndClaimId(viewerUserId, claimId)
                .orElseGet(() -> {
                    try {
                        return chatThreadRepository.save(
                                ChatThread.builder()
                                        .viewerUserId(viewerUserId)
                                        .claimId(claimId)
                                        .build());
                    } catch (org.springframework.dao.DataIntegrityViolationException e) {
                        // Lost the race — another thread inserted first.
                        return chatThreadRepository
                                .findByViewerUserIdAndClaimId(viewerUserId, claimId)
                                .orElseThrow(() -> e);
                    }
                });
    }

    /**
     * List messages for the viewer's thread on a claim. Returns an empty list
     * if no thread exists yet (viewer has never sent a message).
     */
    @Transactional(readOnly = true)
    public List<IntakeMessage> listMessages(User viewer, Long claimId) {
        return chatThreadRepository
                .findByViewerUserIdAndClaimId(viewer.getId(), claimId)
                .map(thread -> messageRepository.findByThreadIdOrderByCreatedAt(thread.getId()))
                .orElse(List.of());
    }
}
