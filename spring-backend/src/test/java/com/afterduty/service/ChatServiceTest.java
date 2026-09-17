package com.afterduty.service;

import com.afterduty.model.Atom;
import com.afterduty.model.Claim;
import com.afterduty.model.ChatThread;
import com.afterduty.model.IntakeMessage;
import com.afterduty.model.User;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ChatThreadRepository;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.MessageRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * TB–TG: Tests for ChatService — thread auto-create/reuse, viewer isolation,
 * billing to viewer (VSO and owner paths), atom provenance, synthesisNeeded flag.
 *
 * Pure Mockito pattern: no Spring context, no H2. All dependencies are @Mock;
 * ChatService is @InjectMocks.
 */
@ExtendWith(MockitoExtension.class)
@Tag("regression")
class ChatServiceTest {

    @Mock ChatAgent chatAgent;
    @Mock ChatThreadRepository chatThreadRepository;
    @Mock MessageRepository messageRepository;
    @Mock AtomRepository atomRepository;
    @Mock ClaimRepository claimRepository;
    @Mock UsageGuard usageGuard;
    @Mock ClaimAccessService claimAccessService;

    @InjectMocks ChatService chatService;

    private static final AtomicLong idGen = new AtomicLong(100L);

    private User makeUser() {
        long id = idGen.incrementAndGet();
        User u = new User();
        u.setId(id);
        u.setEmail("user" + id + "@test.com");
        u.setName("User " + id);
        return u;
    }

    private Claim makeClaim(Long userId) {
        long id = idGen.incrementAndGet();
        Claim c = new Claim();
        c.setId(id);
        c.setUserId(userId);
        c.setSynthesisNeeded(false);
        return c;
    }

    private ChatThread makeThread(Long id, Long viewerUserId, Long claimId) {
        return ChatThread.builder()
                .id(id)
                .viewerUserId(viewerUserId)
                .claimId(claimId)
                .build();
    }

    private IntakeMessage makeMessage(Long id, Long claimId, Long threadId, String role) {
        return IntakeMessage.builder()
                .id(id)
                .claimId(claimId)
                .threadId(threadId)
                .role(role)
                .content("content")
                .build();
    }

    // TB — ChatService.sendMessage: creates a ChatThread on first call, reuses on second
    @Test
    void sendMessage_createsThreadOnFirstCall_reusesOnSecond() {
        User viewer = makeUser();
        Claim claim = makeClaim(viewer.getId());
        ChatThread thread = makeThread(1L, viewer.getId(), claim.getId());

        // First findBy returns empty → save creates thread; second findBy returns existing thread
        when(chatThreadRepository.findByViewerUserIdAndClaimId(viewer.getId(), claim.getId()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(thread));
        when(chatThreadRepository.save(any(ChatThread.class))).thenReturn(thread);

        // messageRepository.save returns distinct messages per call
        IntakeMessage userMsg1 = makeMessage(10L, claim.getId(), 1L, "veteran");
        IntakeMessage asstMsg1 = makeMessage(11L, claim.getId(), 1L, "assistant");
        IntakeMessage userMsg2 = makeMessage(12L, claim.getId(), 1L, "veteran");
        IntakeMessage asstMsg2 = makeMessage(13L, claim.getId(), 1L, "assistant");
        when(messageRepository.save(any(IntakeMessage.class)))
                .thenReturn(userMsg1, asstMsg1, userMsg2, asstMsg2);

        when(chatAgent.handle(any(), any(), any(), any())).thenReturn("reply");
        when(atomRepository.countByClaimIdAndSupersededByIsNull(claim.getId())).thenReturn(0L);

        // First sendMessage — creates the thread
        chatService.sendMessage(viewer, claim.getId(), "first message");

        // Second sendMessage — reuses the thread (findBy now returns existing)
        chatService.sendMessage(viewer, claim.getId(), "second message");

        // chatThreadRepository.save should have been called exactly once (only on thread creation)
        verify(chatThreadRepository, times(1)).save(any(ChatThread.class));

        // messageRepository.save should have been called 4 times (2 turns × 2 messages each)
        verify(messageRepository, times(4)).save(any(IntakeMessage.class));
    }

    // TC — ChatService.listMessages: scoped to viewer's thread, excludes other viewer's messages
    @Test
    void listMessages_scopedToViewerThread_excludesOtherViewerMessages() {
        User owner = makeUser();
        Claim claim = makeClaim(owner.getId());
        User viewer1 = makeUser();
        User viewer2 = makeUser();

        ChatThread thread1 = makeThread(1L, viewer1.getId(), claim.getId());
        ChatThread thread2 = makeThread(2L, viewer2.getId(), claim.getId());

        // Thread creation stubs
        when(chatThreadRepository.findByViewerUserIdAndClaimId(viewer1.getId(), claim.getId()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(thread1));
        when(chatThreadRepository.findByViewerUserIdAndClaimId(viewer2.getId(), claim.getId()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(thread2));
        when(chatThreadRepository.save(any(ChatThread.class)))
                .thenReturn(thread1, thread2);

        // Message saves: viewer1 gets msg ids 20/21, viewer2 gets 22/23
        IntakeMessage v1UserMsg = makeMessage(20L, claim.getId(), 1L, "veteran");
        IntakeMessage v1AsstMsg = makeMessage(21L, claim.getId(), 1L, "assistant");
        IntakeMessage v2UserMsg = makeMessage(22L, claim.getId(), 2L, "veteran");
        IntakeMessage v2AsstMsg = makeMessage(23L, claim.getId(), 2L, "assistant");
        when(messageRepository.save(any(IntakeMessage.class)))
                .thenReturn(v1UserMsg, v1AsstMsg, v2UserMsg, v2AsstMsg);

        when(chatAgent.handle(any(), any(), any(), any())).thenReturn("reply");
        when(atomRepository.countByClaimIdAndSupersededByIsNull(claim.getId())).thenReturn(0L);

        chatService.sendMessage(viewer1, claim.getId(), "viewer1 msg");
        chatService.sendMessage(viewer2, claim.getId(), "viewer2 msg");

        // listMessages stubs
        when(messageRepository.findByThreadIdOrderByCreatedAt(1L))
                .thenReturn(List.of(v1UserMsg, v1AsstMsg));
        when(messageRepository.findByThreadIdOrderByCreatedAt(2L))
                .thenReturn(List.of(v2UserMsg, v2AsstMsg));

        List<IntakeMessage> v1Msgs = chatService.listMessages(viewer1, claim.getId());
        List<IntakeMessage> v2Msgs = chatService.listMessages(viewer2, claim.getId());

        assertThat(v1Msgs).hasSize(2);
        assertThat(v2Msgs).hasSize(2);

        Long thread1Id = v1Msgs.get(0).getThreadId();
        Long thread2Id = v2Msgs.get(0).getThreadId();
        assertThat(thread1Id).isNotEqualTo(thread2Id);

        assertThat(v1Msgs).allMatch(m -> m.getThreadId().equals(thread1Id));
        assertThat(v2Msgs).allMatch(m -> m.getThreadId().equals(thread2Id));
    }

    // TD — sendMessage (VSO): usageGuard called with VSO's userId, not owner's
    @Test
    void sendMessage_vso_usageGuardCalledWithVsoId_notOwnerId() {
        User owner = makeUser();
        User vso = makeUser();
        Claim claim = makeClaim(owner.getId());
        ChatThread thread = makeThread(1L, vso.getId(), claim.getId());

        when(chatThreadRepository.findByViewerUserIdAndClaimId(vso.getId(), claim.getId()))
                .thenReturn(Optional.empty());
        when(chatThreadRepository.save(any(ChatThread.class))).thenReturn(thread);

        IntakeMessage userMsg = makeMessage(10L, claim.getId(), 1L, "veteran");
        IntakeMessage asstMsg = makeMessage(11L, claim.getId(), 1L, "assistant");
        when(messageRepository.save(any(IntakeMessage.class))).thenReturn(userMsg, asstMsg);

        when(chatAgent.handle(any(), any(), any(), any())).thenReturn("reply");
        when(atomRepository.countByClaimIdAndSupersededByIsNull(claim.getId())).thenReturn(0L);

        chatService.sendMessage(vso, claim.getId(), "hello from vso");

        verify(usageGuard).assertCapacity(vso.getId());
        verify(usageGuard, never()).assertCapacity(owner.getId());
    }

    // TE — sendMessage (owner): usageGuard called with owner's userId
    @Test
    void sendMessage_owner_usageGuardCalledWithOwnerId() {
        User owner = makeUser();
        Claim claim = makeClaim(owner.getId());
        ChatThread thread = makeThread(1L, owner.getId(), claim.getId());

        when(chatThreadRepository.findByViewerUserIdAndClaimId(owner.getId(), claim.getId()))
                .thenReturn(Optional.empty());
        when(chatThreadRepository.save(any(ChatThread.class))).thenReturn(thread);

        IntakeMessage userMsg = makeMessage(10L, claim.getId(), 1L, "veteran");
        IntakeMessage asstMsg = makeMessage(11L, claim.getId(), 1L, "assistant");
        when(messageRepository.save(any(IntakeMessage.class))).thenReturn(userMsg, asstMsg);

        when(chatAgent.handle(any(), any(), any(), any())).thenReturn("reply");
        when(atomRepository.countByClaimIdAndSupersededByIsNull(claim.getId())).thenReturn(0L);

        chatService.sendMessage(owner, claim.getId(), "owner msg");

        verify(usageGuard).assertCapacity(owner.getId());
    }

    // TF — sendMessage (VSO add_atom): atom.creatorUserId == vsoId, messageId set, synthesisNeeded=true
    @Test
    void sendMessage_vso_addAtomTool_setsCreatorUserIdAndMessageIdAndSynthesisNeeded() {
        User owner = makeUser();
        User vso = makeUser();
        Claim claim = makeClaim(owner.getId());
        ChatThread thread = makeThread(1L, vso.getId(), claim.getId());

        when(chatThreadRepository.findByViewerUserIdAndClaimId(vso.getId(), claim.getId()))
                .thenReturn(Optional.empty());
        when(chatThreadRepository.save(any(ChatThread.class))).thenReturn(thread);

        // userMsg has id=10 so we can verify atom.messageId
        IntakeMessage userMsg = makeMessage(10L, claim.getId(), 1L, "veteran");
        IntakeMessage asstMsg = makeMessage(11L, claim.getId(), 1L, "assistant");
        when(messageRepository.save(any(IntakeMessage.class))).thenReturn(userMsg, asstMsg);

        // Simulate atom created by ChatAgent: count goes 0 → 1 between the two live-atom-count calls
        when(atomRepository.countByClaimIdAndSupersededByIsNull(claim.getId())).thenReturn(0L, 1L);

        // Build the atom that represents what the agent created
        Atom createdAtom = new Atom();
        createdAtom.setClaimId(claim.getId());
        createdAtom.setType("diagnosis");
        createdAtom.setValue("tinnitus");
        createdAtom.setSource("chat");
        createdAtom.setCreatedBy("ai:claude");
        createdAtom.setCreatorUserId(vso.getId());
        createdAtom.setMessageId(10L); // matches userMsg.id
        when(atomRepository.findByClaimId(claim.getId())).thenReturn(List.of(createdAtom));

        // claimRepository.findById returns a real Claim so setSynthesisNeeded can be verified
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
        when(claimRepository.save(any(Claim.class))).thenReturn(claim);

        doAnswer(invocation -> "I found an atom")
                .when(chatAgent).handle(any(), eq(claim.getId()), eq(vso.getId()), any());

        chatService.sendMessage(vso, claim.getId(), "I have tinnitus");

        List<Atom> atoms = atomRepository.findByClaimId(claim.getId());
        assertThat(atoms).hasSize(1);
        assertThat(atoms.get(0).getCreatorUserId()).isEqualTo(vso.getId());
        assertThat(atoms.get(0).getMessageId()).isNotNull();

        // Verify synthesisNeeded was set to true on the saved claim
        ArgumentCaptor<Claim> claimCaptor = ArgumentCaptor.forClass(Claim.class);
        verify(claimRepository).save(claimCaptor.capture());
        assertThat(claimCaptor.getValue().getSynthesisNeeded()).isTrue();
    }

    // ---- Increment 7 §E.4: prepareTurn / completeTurn split ----

    // TH — prepareTurn bills the viewer, persists the veteran message, snapshots atoms.
    @Test
    void prepareTurn_billsViewer_persistsVeteranMessage_snapshotsAtoms() {
        User viewer = makeUser();
        Claim claim = makeClaim(viewer.getId());
        ChatThread thread = makeThread(1L, viewer.getId(), claim.getId());

        when(chatThreadRepository.findByViewerUserIdAndClaimId(viewer.getId(), claim.getId()))
                .thenReturn(Optional.empty());
        when(chatThreadRepository.save(any(ChatThread.class))).thenReturn(thread);
        IntakeMessage userMsg = makeMessage(10L, claim.getId(), 1L, "veteran");
        when(messageRepository.save(any(IntakeMessage.class))).thenReturn(userMsg);
        when(atomRepository.countByClaimIdAndSupersededByIsNull(claim.getId())).thenReturn(3L);

        ChatService.TurnContext ctx = chatService.prepareTurn(viewer, claim.getId(), "hello");

        verify(usageGuard).assertCapacity(viewer.getId());
        assertThat(ctx.userMsg().getId()).isEqualTo(10L);
        assertThat(ctx.thread().getId()).isEqualTo(1L);
        assertThat(ctx.atomsBefore()).isEqualTo(3L);

        // Only the veteran message persisted in prepareTurn — no assistant message yet.
        ArgumentCaptor<IntakeMessage> cap = ArgumentCaptor.forClass(IntakeMessage.class);
        verify(messageRepository).save(cap.capture());
        assertThat(cap.getValue().getRole()).isEqualTo("veteran");
    }

    // TI — completeTurn persists the assistant reply and flips synthesisNeeded on atom delta.
    @Test
    void completeTurn_persistsAssistant_setsSynthesisNeeded_whenAtomsGrew() {
        User viewer = makeUser();
        Claim claim = makeClaim(viewer.getId());
        ChatThread thread = makeThread(1L, viewer.getId(), claim.getId());
        IntakeMessage userMsg = makeMessage(10L, claim.getId(), 1L, "veteran");
        IntakeMessage asstMsg = makeMessage(11L, claim.getId(), 1L, "assistant");

        ChatService.TurnContext ctx = new ChatService.TurnContext(thread, userMsg, 2L);
        when(messageRepository.save(any(IntakeMessage.class))).thenReturn(asstMsg);
        // Atoms grew 2 → 3 → synthesisNeeded must flip.
        when(atomRepository.countByClaimIdAndSupersededByIsNull(claim.getId())).thenReturn(3L);
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
        when(claimRepository.save(any(Claim.class))).thenReturn(claim);

        IntakeMessage result = chatService.completeTurn(viewer, claim.getId(), ctx, "the reply");

        assertThat(result.getId()).isEqualTo(11L);
        ArgumentCaptor<IntakeMessage> cap = ArgumentCaptor.forClass(IntakeMessage.class);
        verify(messageRepository).save(cap.capture());
        assertThat(cap.getValue().getRole()).isEqualTo("assistant");
        assertThat(cap.getValue().getContent()).isEqualTo("the reply");

        ArgumentCaptor<Claim> claimCap = ArgumentCaptor.forClass(Claim.class);
        verify(claimRepository).save(claimCap.capture());
        assertThat(claimCap.getValue().getSynthesisNeeded()).isTrue();
    }

    // TJ — completeTurn does NOT flip synthesisNeeded when atom count is unchanged.
    @Test
    void completeTurn_noSynthesis_whenAtomsUnchanged() {
        User viewer = makeUser();
        Claim claim = makeClaim(viewer.getId());
        ChatThread thread = makeThread(1L, viewer.getId(), claim.getId());
        IntakeMessage userMsg = makeMessage(10L, claim.getId(), 1L, "veteran");
        IntakeMessage asstMsg = makeMessage(11L, claim.getId(), 1L, "assistant");

        ChatService.TurnContext ctx = new ChatService.TurnContext(thread, userMsg, 5L);
        when(messageRepository.save(any(IntakeMessage.class))).thenReturn(asstMsg);
        when(atomRepository.countByClaimIdAndSupersededByIsNull(claim.getId())).thenReturn(5L);

        chatService.completeTurn(viewer, claim.getId(), ctx, "no new atoms");

        verify(claimRepository, never()).save(any(Claim.class));
    }

    // TK — sendMessage preserves the veteran message + persists a fallback reply on agent failure.
    @Test
    void sendMessage_persistsVeteranAndFallback_whenAgentThrows() {
        User owner = makeUser();
        Claim claim = makeClaim(owner.getId());
        ChatThread thread = makeThread(1L, owner.getId(), claim.getId());

        when(chatThreadRepository.findByViewerUserIdAndClaimId(owner.getId(), claim.getId()))
                .thenReturn(Optional.empty());
        when(chatThreadRepository.save(any(ChatThread.class))).thenReturn(thread);
        IntakeMessage userMsg = makeMessage(10L, claim.getId(), 1L, "veteran");
        IntakeMessage asstMsg = makeMessage(11L, claim.getId(), 1L, "assistant");
        when(messageRepository.save(any(IntakeMessage.class))).thenReturn(userMsg, asstMsg);
        when(atomRepository.countByClaimIdAndSupersededByIsNull(claim.getId())).thenReturn(0L);

        // Agent blows up — the user message must still persist and a fallback reply saved.
        when(chatAgent.handle(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("LLM down"));

        var result = chatService.sendMessage(owner, claim.getId(), "hello");

        // Both messages persisted (veteran + fallback assistant).
        verify(messageRepository, times(2)).save(any(IntakeMessage.class));
        assertThat(result).containsKey("veteran_message");
        assertThat(result).containsKey("assistant_message");
        // The persisted assistant reply is the fallback text.
        ArgumentCaptor<IntakeMessage> cap = ArgumentCaptor.forClass(IntakeMessage.class);
        verify(messageRepository, times(2)).save(cap.capture());
        assertThat(cap.getAllValues().get(1).getContent())
                .isEqualTo(ChatService.AGENT_FALLBACK_REPLY);
    }

    // P1-10 — provider brownout (bounded retries exhausted) persists the CAPACITY-specific
    // fallback, not the generic "temporarily unavailable", so the transcript tells the
    // veteran to simply retry.
    @Test
    void sendMessage_persistsRateLimitedFallback_whenAgentRateLimited() {
        User owner = makeUser();
        Claim claim = makeClaim(owner.getId());
        ChatThread thread = makeThread(1L, owner.getId(), claim.getId());

        when(chatThreadRepository.findByViewerUserIdAndClaimId(owner.getId(), claim.getId()))
                .thenReturn(Optional.empty());
        when(chatThreadRepository.save(any(ChatThread.class))).thenReturn(thread);
        IntakeMessage userMsg = makeMessage(10L, claim.getId(), 1L, "veteran");
        IntakeMessage asstMsg = makeMessage(11L, claim.getId(), 1L, "assistant");
        when(messageRepository.save(any(IntakeMessage.class))).thenReturn(userMsg, asstMsg);
        when(atomRepository.countByClaimIdAndSupersededByIsNull(claim.getId())).thenReturn(0L);

        when(chatAgent.handle(any(), any(), any(), any()))
                .thenThrow(new ChatAgent.ChatRateLimitedException("rate-limited 3 times", null));

        chatService.sendMessage(owner, claim.getId(), "hello");

        ArgumentCaptor<IntakeMessage> cap = ArgumentCaptor.forClass(IntakeMessage.class);
        verify(messageRepository, times(2)).save(cap.capture());
        assertThat(cap.getAllValues().get(1).getContent())
                .isEqualTo(ChatService.RATE_LIMITED_FALLBACK_REPLY)
                .isNotEqualTo(ChatService.AGENT_FALLBACK_REPLY);
    }

    // ---- Increment 7 §E.3 owner Pro-gate, shared layer (adversarial-review critical) ----

    // TL — prepareTurn gates the owner own-claim path: free owner ⇒ 402 from the shared
    // guard, BEFORE any usage billing or message persistence. This closes the legacy
    // POST /api/claim/chat bypass (IntakeController own-claim path skips assertScope),
    // mirroring the streaming controller's owner gate (acceptance §H.4-6).
    @Test
    void prepareTurn_owner_freeTier_throws402_beforePersistOrBilling() {
        User owner = makeUser();
        Claim claim = makeClaim(owner.getId());

        // Claim resolves and the viewer IS the owner → the owner gate must fire.
        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
        // The shared guard denies a free owner with 402 subscription_required.
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.PAYMENT_REQUIRED, "subscription_required"))
                .when(claimAccessService).assertOwnerChatAllowed(owner);

        org.springframework.web.server.ResponseStatusException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () -> chatService.prepareTurn(owner, claim.getId(), "hello"));
        assertThat(ex.getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.PAYMENT_REQUIRED);
        assertThat(ex.getReason()).isEqualTo("subscription_required");

        // Gate fires before billing and before any message is persisted.
        verify(usageGuard, never()).assertCapacity(any());
        verify(messageRepository, never()).save(any(IntakeMessage.class));
        verify(chatThreadRepository, never()).save(any(ChatThread.class));
    }

    // TM — prepareTurn lets a subscribed owner through (guard passes) and proceeds normally.
    @Test
    void prepareTurn_owner_proTier_passesGuard_andProceeds() {
        User owner = makeUser();
        Claim claim = makeClaim(owner.getId());
        ChatThread thread = makeThread(1L, owner.getId(), claim.getId());

        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
        // Guard is a no-op (subscribed owner) — default mock behavior.
        when(chatThreadRepository.findByViewerUserIdAndClaimId(owner.getId(), claim.getId()))
                .thenReturn(Optional.empty());
        when(chatThreadRepository.save(any(ChatThread.class))).thenReturn(thread);
        IntakeMessage userMsg = makeMessage(10L, claim.getId(), 1L, "veteran");
        when(messageRepository.save(any(IntakeMessage.class))).thenReturn(userMsg);
        when(atomRepository.countByClaimIdAndSupersededByIsNull(claim.getId())).thenReturn(0L);

        ChatService.TurnContext ctx = chatService.prepareTurn(owner, claim.getId(), "hello");

        verify(claimAccessService).assertOwnerChatAllowed(owner);
        verify(usageGuard).assertCapacity(owner.getId());
        assertThat(ctx.userMsg().getId()).isEqualTo(10L);
    }

    // TN — prepareTurn does NOT apply the owner gate on the viewer/VSO path (viewer != owner):
    // that path is gated upstream by assertScope(CHAT) in both controllers. The shared owner
    // guard must never fire for a non-owner viewer here.
    @Test
    void prepareTurn_vsoViewer_doesNotInvokeOwnerGate() {
        User owner = makeUser();
        User vso = makeUser();
        Claim claim = makeClaim(owner.getId());
        ChatThread thread = makeThread(1L, vso.getId(), claim.getId());

        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
        when(chatThreadRepository.findByViewerUserIdAndClaimId(vso.getId(), claim.getId()))
                .thenReturn(Optional.empty());
        when(chatThreadRepository.save(any(ChatThread.class))).thenReturn(thread);
        IntakeMessage userMsg = makeMessage(10L, claim.getId(), 1L, "veteran");
        when(messageRepository.save(any(IntakeMessage.class))).thenReturn(userMsg);
        when(atomRepository.countByClaimIdAndSupersededByIsNull(claim.getId())).thenReturn(0L);

        chatService.prepareTurn(vso, claim.getId(), "hello from vso");

        // The owner gate is owner-only; the viewer is gated upstream, never here.
        verify(claimAccessService, never()).assertOwnerChatAllowed(any());
        verify(usageGuard).assertCapacity(vso.getId());
    }

    // TG — sendMessage (owner add_atom): atom.creatorUserId == ownerId
    @Test
    void sendMessage_owner_addAtomTool_setsCreatorUserIdToOwnerId() {
        User owner = makeUser();
        Claim claim = makeClaim(owner.getId());
        ChatThread thread = makeThread(1L, owner.getId(), claim.getId());

        when(chatThreadRepository.findByViewerUserIdAndClaimId(owner.getId(), claim.getId()))
                .thenReturn(Optional.empty());
        when(chatThreadRepository.save(any(ChatThread.class))).thenReturn(thread);

        IntakeMessage userMsg = makeMessage(10L, claim.getId(), 1L, "veteran");
        IntakeMessage asstMsg = makeMessage(11L, claim.getId(), 1L, "assistant");
        when(messageRepository.save(any(IntakeMessage.class))).thenReturn(userMsg, asstMsg);

        // Simulate atom created by ChatAgent: count goes 0 → 1
        when(atomRepository.countByClaimIdAndSupersededByIsNull(claim.getId())).thenReturn(0L, 1L);

        Atom createdAtom = new Atom();
        createdAtom.setClaimId(claim.getId());
        createdAtom.setType("diagnosis");
        createdAtom.setValue("knee pain");
        createdAtom.setSource("chat");
        createdAtom.setCreatedBy("ai:claude");
        createdAtom.setCreatorUserId(owner.getId());
        createdAtom.setMessageId(10L);
        when(atomRepository.findByClaimId(claim.getId())).thenReturn(List.of(createdAtom));

        when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
        when(claimRepository.save(any(Claim.class))).thenReturn(claim);

        doAnswer(invocation -> "noted")
                .when(chatAgent).handle(any(), eq(claim.getId()), eq(owner.getId()), any());

        chatService.sendMessage(owner, claim.getId(), "I have knee pain");

        List<Atom> atoms = atomRepository.findByClaimId(claim.getId());
        assertThat(atoms).hasSize(1);
        assertThat(atoms.get(0).getCreatorUserId()).isEqualTo(owner.getId());
    }
}
