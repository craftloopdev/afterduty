package com.afterduty.service;

import com.afterduty.model.AiCallLog;
import com.afterduty.model.Atom;
import com.afterduty.model.ChatThread;
import com.afterduty.model.Claim;
import com.afterduty.model.ClaimPipelineJob;
import com.afterduty.model.ClaimScenario;
import com.afterduty.model.EvidenceItem;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.model.IntakeMessage;
import com.afterduty.model.LlmJob;
import com.afterduty.model.MedicalEvent;
import com.afterduty.model.Notification;
import com.afterduty.model.PipelineMetrics;
import com.afterduty.model.ServiceProfile;
import com.afterduty.model.Share;
import com.afterduty.model.User;
import com.afterduty.model.WebAuthnChallenge;
import com.afterduty.model.WebAuthnCredential;
import com.afterduty.repository.AiCallLogRepository;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ChatThreadRepository;
import com.afterduty.repository.ClaimPipelineJobRepository;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.ConditionRepository;
import com.afterduty.repository.EvidenceItemRepository;
import com.afterduty.repository.LlmJobRepository;
import com.afterduty.repository.MedicalEventRepository;
import com.afterduty.repository.MessageRepository;
import com.afterduty.repository.NotificationRepository;
import com.afterduty.repository.PipelineMetricsRepository;
import com.afterduty.repository.ScenarioRepository;
import com.afterduty.repository.ServiceProfileRepository;
import com.afterduty.repository.ShareRepository;
import com.afterduty.repository.UserRepository;
import com.afterduty.repository.WebAuthnChallengeRepository;
import com.afterduty.repository.WebAuthnCredentialRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link UserDeletionService} performs COMPLETE account deletion
 * (Apple Guideline 5.1.1(v)): every row of the user's personal data is removed,
 * no FK violation is thrown, and the operation is idempotent.
 *
 * <p>External integrations are mocked: {@link StripeService} (unconfigured in
 * test) and {@link DocumentStorageService} (GCS) are {@code @MockitoBean}s, and
 * the test user's firebaseUid starts with {@code "dev_"} so the Firebase Auth
 * delete is skipped (never reaches {@code FirebaseAuth.getInstance()}).
 */
@DataJpaTest
@Import(UserDeletionService.class)
@Tag("regression")
class UserDeletionServiceTest {

    @MockitoBean
    StripeService stripeService;

    @MockitoBean
    DocumentStorageService documentStorageService;

    @Autowired UserDeletionService userDeletionService;

    @Autowired UserRepository userRepository;
    @Autowired ClaimRepository claimRepository;
    @Autowired EvidenceItemRepository evidenceItemRepository;
    @Autowired ConditionRepository conditionRepository;
    @Autowired AtomRepository atomRepository;
    @Autowired MessageRepository messageRepository;
    @Autowired ChatThreadRepository chatThreadRepository;
    @Autowired ShareRepository shareRepository;
    @Autowired ScenarioRepository scenarioRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired AiCallLogRepository aiCallLogRepository;
    @Autowired LlmJobRepository llmJobRepository;
    @Autowired ServiceProfileRepository serviceProfileRepository;
    @Autowired MedicalEventRepository medicalEventRepository;
    @Autowired PipelineMetricsRepository pipelineMetricsRepository;
    @Autowired ClaimPipelineJobRepository claimPipelineJobRepository;
    @Autowired WebAuthnCredentialRepository webAuthnCredentialRepository;
    @Autowired WebAuthnChallengeRepository webAuthnChallengeRepository;
    @Autowired com.afterduty.repository.DeviceCredentialRepository deviceCredentialRepository;

    // ------------------------------------------------------------------
    // Fixture builder — a fully-populated user-data graph.
    // ------------------------------------------------------------------

    private record Fixture(Long userId, Long claimId, Long evidenceId,
                           Long conditionId, Long threadId) {}

    private User makeUser() {
        String suffix = "-" + System.nanoTime();
        User u = User.builder()
                .email("del" + suffix + "@test.com")
                .name("Del Test")
                .firebaseUid("dev_del" + suffix)   // dev uid → Firebase delete skipped
                .build();
        u.setStripeCustomerId("cus_" + suffix);
        return userRepository.save(u);
    }

    private Fixture seedFullGraph(User owner) {
        Long userId = owner.getId();

        Claim claim = claimRepository.save(Claim.builder().userId(userId).build());
        Long claimId = claim.getId();

        EvidenceItem ev = evidenceItemRepository.save(EvidenceItem.builder()
                .claimId(claimId).sourceType("upload").gcsPath(userId + "/" + claimId + "/1.pdf")
                .build());
        Long evidenceId = ev.getId();

        IdentifiedCondition cond = conditionRepository.save(buildCondition(claimId));
        Long conditionId = cond.getId();

        // Atom on the user's own claim, linked to the evidence.
        atomRepository.save(Atom.builder()
                .claimId(claimId).evidenceId(evidenceId).creatorUserId(userId)
                .type("diagnosis").value("PTSD").source("evidence:1").build());

        ChatThread thread = chatThreadRepository.save(ChatThread.builder()
                .viewerUserId(userId).claimId(claimId).build());
        Long threadId = thread.getId();

        messageRepository.save(IntakeMessage.builder()
                .claimId(claimId).threadId(threadId).role("user").content("hello").build());

        shareRepository.save(Share.builder()
                .ownerUserId(userId).claimId(claimId).viewerEmail("v" + System.nanoTime() + "@t.com")
                .build());

        scenarioRepository.save(ClaimScenario.builder().userId(userId).name("Scenario A").build());

        notificationRepository.save(Notification.builder()
                .userId(userId).claimId(claimId).eventType("analysis_complete")
                .title("Done").body("Analysis ready").build());

        aiCallLogRepository.save(AiCallLog.builder()
                .userId(userId).claimId(claimId).callType("synthesis")
                .provider("claude").modelName("claude-x").build());

        llmJobRepository.save(LlmJob.builder()
                .id(UUID.randomUUID()).provider("anthropic").modelName("claude-x")
                .purpose("synthesis_identify").claimId(claimId).userId(userId)
                .requestPayload("{}").build());

        serviceProfileRepository.save(ServiceProfile.builder().user(owner).branch("Army").build());

        medicalEventRepository.save(MedicalEvent.builder()
                .claimId(claimId).evidenceId(evidenceId).eventType("visit").build());

        PipelineMetrics pm = new PipelineMetrics();
        pm.setClaimId(claimId);
        pipelineMetricsRepository.save(pm);

        claimPipelineJobRepository.save(
                new ClaimPipelineJob(claimId, "synthesis_identify", UUID.randomUUID(), null, null));

        return new Fixture(userId, claimId, evidenceId, conditionId, threadId);
    }

    private IdentifiedCondition buildCondition(Long claimId) {
        IdentifiedCondition c = new IdentifiedCondition();
        c.setClaimId(claimId);
        c.setName("PTSD");
        return c;
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    /**
     * Regression for the self-invocation transaction bug (production
     * {@code TransactionRequiredException: No active transaction}). In
     * production the controller calls {@link UserDeletionService#deleteAccount}
     * with NO ambient transaction, so the service must open its own around the
     * bulk deletes. Every <em>other</em> test here runs inside
     * {@code @DataJpaTest}'s ambient transaction, which masked the missing one.
     * This test disables that ambient transaction ({@code NOT_SUPPORTED}) to
     * exercise the real request path: against the pre-fix code (bulk JPQL with
     * no active transaction) it throws; with the {@code TransactionTemplate}
     * fix the deletion commits.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deleteAccount_withNoAmbientTransaction_commits() {
        when(stripeService.cancelActiveSubscriptions(org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);

        User owner = makeUser();
        Fixture fx = seedFullGraph(owner);
        assertThat(userRepository.findById(fx.userId())).isPresent();

        // The whole point: deleteAccount establishes its own transaction.
        assertDoesNotThrow(() -> userDeletionService.deleteAccount(owner));

        // Deletion actually COMMITTED (there's no ambient tx to roll back) —
        // the rows are gone when read back in fresh transactions.
        assertThat(userRepository.findById(fx.userId())).isEmpty();
        assertThat(claimRepository.findById(fx.claimId())).isEmpty();
        assertThat(atomRepository.findByClaimId(fx.claimId())).isEmpty();
        assertThat(serviceProfileRepository.findByUserId(fx.userId())).isEmpty();
    }

    @Test
    void deleteAccount_removesEveryUserRow_andDoesNotThrow() {
        when(stripeService.cancelActiveSubscriptions(org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);

        User owner = makeUser();
        Fixture fx = seedFullGraph(owner);

        // Sanity: the graph really is populated before deletion.
        assertThat(claimRepository.findById(fx.claimId())).isPresent();
        assertThat(atomRepository.findByClaimId(fx.claimId())).isNotEmpty();

        assertDoesNotThrow(() -> userDeletionService.deleteAccount(owner));

        // Every row of the user's data is gone.
        assertThat(userRepository.findById(fx.userId())).isEmpty();
        assertThat(claimRepository.findById(fx.claimId())).isEmpty();
        assertThat(evidenceItemRepository.findById(fx.evidenceId())).isEmpty();
        assertThat(conditionRepository.findByClaimId(fx.claimId())).isEmpty();
        assertThat(atomRepository.findByClaimId(fx.claimId())).isEmpty();
        assertThat(messageRepository.findByClaimIdOrderByCreatedAt(fx.claimId())).isEmpty();
        assertThat(chatThreadRepository.findById(fx.threadId())).isEmpty();
        assertThat(shareRepository.findByOwnerUserIdAndRevokedAtIsNull(fx.userId())).isEmpty();
        assertThat(scenarioRepository.findByUserIdOrderByCreatedAtDesc(fx.userId())).isEmpty();
        assertThat(notificationRepository.findByUserIdOrderByCreatedAtDesc(fx.userId())).isEmpty();
        assertThat(aiCallLogRepository.findByUserIdOrderByCreatedAtDesc(fx.userId())).isEmpty();
        assertThat(aiCallLogRepository.findByClaimIdOrderByCreatedAtDesc(fx.claimId())).isEmpty();
        assertThat(serviceProfileRepository.findByUserId(fx.userId())).isEmpty();
        assertThat(medicalEventRepository.findByClaimId(fx.claimId())).isEmpty();
        assertThat(pipelineMetricsRepository.findByClaimId(fx.claimId())).isEmpty();
        assertThat(claimPipelineJobRepository.findByClaimIdAndStage(fx.claimId(), "synthesis_identify"))
                .isEmpty();
        // llm_jobs for the user are gone — verify via full scan.
        assertThat(llmJobRepository.findAll().stream()
                .anyMatch(j -> fx.userId().equals(j.getUserId()))).isFalse();
    }

    @Test
    void deleteAccount_cancelsStripeAndDeletesGcsDocuments() {
        when(stripeService.cancelActiveSubscriptions(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);

        User owner = makeUser();
        Fixture fx = seedFullGraph(owner);
        String expectedPath = fx.userId() + "/" + fx.claimId() + "/1.pdf";

        userDeletionService.deleteAccount(owner);

        // Stripe cancellation was attempted with the user.
        verify(stripeService).cancelActiveSubscriptions(owner);
        // The evidence file's GCS path was deleted before the row was removed.
        verify(documentStorageService).deleteFromGcs(expectedPath);
    }

    @Test
    void deleteAccount_authoredAtomsOnOtherUsersClaim_areRemoved() {
        when(stripeService.cancelActiveSubscriptions(org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);

        // Another user owns a claim; our user authored an atom on it (a share scenario).
        User other = makeUser();
        Claim otherClaim = claimRepository.save(Claim.builder().userId(other.getId()).build());

        User owner = makeUser();
        seedFullGraph(owner);
        Atom crossAtom = atomRepository.save(Atom.builder()
                .claimId(otherClaim.getId()).creatorUserId(owner.getId())
                .type("note").value("viewer note").source("chat").build());

        assertDoesNotThrow(() -> userDeletionService.deleteAccount(owner));

        // The cross-referencing atom (which would block the users-row delete) is gone,
        // but the other user's claim itself is untouched.
        assertThat(atomRepository.findById(crossAtom.getId())).isEmpty();
        assertThat(claimRepository.findById(otherClaim.getId())).isPresent();
        assertThat(userRepository.findById(other.getId())).isPresent();
    }

    @Test
    void deleteAccount_llmJobOnUsersClaimWithForeignUserId_isRemoved() {
        when(stripeService.cancelActiveSubscriptions(org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);

        User owner = makeUser();
        Fixture fx = seedFullGraph(owner);

        // A job tagged to the user's claim but carrying a DIFFERENT user_id (e.g.
        // a pipeline run on a shared claim). Its payloads hold the owner's claim
        // data, so a complete wipe must remove it via the claimId pass — the
        // userId pass alone would leave it behind.
        UUID foreignJobId = UUID.randomUUID();
        llmJobRepository.save(LlmJob.builder()
                .id(foreignJobId).provider("anthropic").modelName("claude-x")
                .purpose("synthesis_identify").claimId(fx.claimId()).userId(999_999_999L)
                .requestPayload("{}").build());

        userDeletionService.deleteAccount(owner);

        assertThat(llmJobRepository.findById(foreignJobId)).isEmpty();
    }

    /**
     * Regression (adversarial WebAuthn review): account deletion MUST remove the
     * user's passkey rows. Leaving them behind is both an incomplete wipe (Apple
     * 5.1.1(v)/GDPR) AND a security hole — {@code credential_id} carries a global
     * UNIQUE index and the assert path resolves purely by credential id + the
     * deterministic per-user handle, so an orphaned row keeps a credential id
     * claimed forever (blocking re-enroll with a 409) and, on any user-id reuse,
     * could authenticate a stale credential onto a new account. Both the enrolled
     * credentials and any in-flight challenges must be gone after deletion.
     */
    @Test
    void deleteAccount_removesWebAuthnCredentialsAndChallenges() {
        when(stripeService.cancelActiveSubscriptions(org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);

        User owner = makeUser();
        Fixture fx = seedFullGraph(owner);

        WebAuthnCredential cred = new WebAuthnCredential();
        cred.setUserId(fx.userId());
        cred.setCredentialId("cred-" + System.nanoTime());
        cred.setUserHandle("handle-" + System.nanoTime());
        cred.setPublicKeyCose("cose");
        cred.setSignatureCount(0L);
        cred.setCreatedAt(java.time.Instant.now());
        webAuthnCredentialRepository.save(cred);

        WebAuthnChallenge ch = new WebAuthnChallenge();
        ch.setChallenge("chal-" + System.nanoTime());
        ch.setCeremony(WebAuthnChallenge.CEREMONY_ASSERT);
        ch.setUserId(fx.userId());
        ch.setRequestJson("{}");
        ch.setCreatedAt(java.time.Instant.now());
        ch.setExpiresAt(java.time.Instant.now().plusSeconds(300));
        webAuthnChallengeRepository.save(ch);

        Long credId = cred.getId();
        Long chalId = ch.getId();
        assertThat(webAuthnCredentialRepository.findById(credId)).isPresent();
        assertThat(webAuthnChallengeRepository.findById(chalId)).isPresent();

        userDeletionService.deleteAccount(owner);

        assertThat(webAuthnCredentialRepository.findByUserId(fx.userId())).isEmpty();
        assertThat(webAuthnCredentialRepository.findById(credId)).isEmpty();
        assertThat(webAuthnChallengeRepository.findById(chalId)).isEmpty();
    }

    /**
     * Account deletion must also wipe device_credentials (P1.4 / B2) — each row
     * holds a biometric device-login secret hash and /device/exchange mints a
     * session from it; an orphan is an incomplete wipe + a stale-credential-onto-
     * reused-id hazard (same class as the passkey rows above).
     */
    @Test
    void deleteAccount_removesDeviceCredentials() {
        when(stripeService.cancelActiveSubscriptions(org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);

        User owner = makeUser();
        Fixture fx = seedFullGraph(owner);

        com.afterduty.model.DeviceCredential dc = new com.afterduty.model.DeviceCredential();
        dc.setUserId(fx.userId());
        dc.setDeviceSecretHash("hash-" + System.nanoTime());
        dc.setDeviceName("This iPhone");
        dc.setPlatform("ios");
        dc.setCreatedAt(java.time.Instant.now());
        deviceCredentialRepository.save(dc);

        Long dcId = dc.getId();
        assertThat(deviceCredentialRepository.findById(dcId)).isPresent();

        userDeletionService.deleteAccount(owner);

        assertThat(deviceCredentialRepository.findByUserId(fx.userId())).isEmpty();
        assertThat(deviceCredentialRepository.findById(dcId)).isEmpty();
    }

    @Test
    void deleteAccount_isIdempotent_secondCallAndUnknownUserAreNoOps() {
        when(stripeService.cancelActiveSubscriptions(org.mockito.ArgumentMatchers.any()))
                .thenReturn(0);

        User owner = makeUser();
        Fixture fx = seedFullGraph(owner);

        userDeletionService.deleteAccount(owner);
        assertThat(userRepository.findById(fx.userId())).isEmpty();

        // Re-running deletion for the already-deleted user must not throw.
        assertDoesNotThrow(() -> userDeletionService.deleteAllUserData(fx.userId()));

        // Deleting data for a never-existed user id is also safe.
        assertDoesNotThrow(() -> userDeletionService.deleteAllUserData(999_999_999L));
    }
}
