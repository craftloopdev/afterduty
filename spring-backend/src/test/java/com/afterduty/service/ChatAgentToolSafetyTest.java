package com.afterduty.service;

import com.afterduty.model.Claim;
import com.afterduty.model.ConditionSuppression;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.model.ServiceProfile;
import com.afterduty.model.User;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.ConditionRepository;
import com.afterduty.repository.ConditionSuppressionRepository;
import com.afterduty.repository.EvidenceRepository;
import com.afterduty.repository.MessageRepository;
import com.afterduty.repository.ServiceProfileRepository;
import com.afterduty.repository.UserRepository;
import com.afterduty.repository.VasrdRecordRepository;
import com.afterduty.service.rag.HybridRetrievalService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-14 — chat mutating-tool safety. Before this change: {@code update_condition}
 * accepted a direct {@code estimated_rating} write (an LLM writing a veteran-visible
 * number), {@code delete_condition} hard-deleted with no undo and the condition
 * resurrected on the next identify run, and writes aimed at superseded rows silently
 * landed on retired generations (an invisible no-op). Verifies:
 * <ul>
 *   <li>the update schema no longer offers {@code estimated_rating}; an attempted
 *       write is refused and marks the condition dirty so the pipeline re-rates;</li>
 *   <li>deletes are soft (SUPPRESSED_MARKER + durable fingerprint-keyed suppression
 *       row) and undoable via {@code restore_condition};</li>
 *   <li>tool calls targeting superseded rows get a self-correcting rejection that
 *       includes the current list.</li>
 * </ul>
 */
@Tag("regression")
class ChatAgentToolSafetyTest {

    private final AtomRepository atomRepo = Mockito.mock(AtomRepository.class);
    private final ConditionRepository condRepo = Mockito.mock(ConditionRepository.class);
    private final ClaimRepository claimRepo = Mockito.mock(ClaimRepository.class);
    private final ConditionSuppressionRepository suppressionRepo =
            Mockito.mock(ConditionSuppressionRepository.class);
    // Increment C — record_service_fact writes the owner's ServiceProfile; UserRepository
    // is used only to attach the owning User when upserting a brand-new profile row.
    private final ServiceProfileRepository serviceProfileRepo =
            Mockito.mock(ServiceProfileRepository.class);
    private final UserRepository userRepo = Mockito.mock(UserRepository.class);

    private ChatAgent agent() {
        // P1-19: executeTool now guards mutating tools by ownership. These tests
        // invoke with (claimId=7, userId=1) as the OWNER — default-stub that;
        // individual tests re-stub findById(7L) when they need a specific claim.
        Claim ownerClaim = new Claim();
        ownerClaim.setId(7L);
        ownerClaim.setUserId(1L);
        Mockito.when(claimRepo.findById(7L)).thenReturn(Optional.of(ownerClaim));
        return new ChatAgent(atomRepo, condRepo,
                Mockito.mock(MessageRepository.class), Mockito.mock(AiCostService.class),
                Mockito.mock(HybridRetrievalService.class),
                Mockito.mock(VasrdRecordRepository.class),
                Mockito.mock(VasrdDataService.class),
                Mockito.mock(EvidenceRepository.class),
                claimRepo, suppressionRepo, serviceProfileRepo, userRepo);
    }

    private String execTool(ChatAgent a, String name, Map<String, Object> input) throws Exception {
        Method m = ChatAgent.class.getDeclaredMethod("executeTool",
                String.class, Map.class, Long.class, Long.class, Long.class);
        m.setAccessible(true);
        return (String) m.invoke(a, name, input, 7L, 1L, 100L);
    }

    private IdentifiedCondition cond(Long id, String name, Integer rating, String fingerprint) {
        IdentifiedCondition c = new IdentifiedCondition();
        c.setId(id);
        c.setClaimId(7L);
        c.setName(name);
        c.setEstimatedRating(rating);
        c.setIdentityFingerprint(fingerprint);
        c.setEvidenceFingerprint("ev-fp");
        c.setLastFullRunAt(Instant.parse("2026-06-01T00:00:00Z"));
        return c;
    }

    // ---- estimated_rating removed from the update schema; attempts mark dirty ----

    @Test
    @SuppressWarnings("unchecked")
    void updateConditionSchema_hasNoEstimatedRatingProperty() throws Exception {
        Method m = ChatAgent.class.getDeclaredMethod("buildToolSchema");
        m.setAccessible(true);
        List<Map<String, Object>> tools = (List<Map<String, Object>>) m.invoke(agent());

        Map<String, Object> update = tools.stream()
                .filter(t -> "update_condition".equals(t.get("name"))).findFirst().orElseThrow();
        Map<String, Object> schema = (Map<String, Object>) update.get("input_schema");
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");

        assertThat(props).doesNotContainKey("estimated_rating");
        assertThat(props).containsKeys("condition_id", "name", "vasrd_code", "rating_rationale");
    }

    @Test
    void updateCondition_attemptedRatingWrite_isRefused_andMarksDirty() throws Exception {
        ChatAgent a = agent();
        IdentifiedCondition c = cond(11L, "Tinnitus", 10, "fp-tinnitus");
        Claim claim = new Claim();
        claim.setId(7L);
        claim.setUserId(1L); // executeTool caller is user 1 — owner (P1-19 guard)
        claim.setSynthesisNeeded(false);
        when(condRepo.findById(11L)).thenReturn(Optional.of(c));
        when(claimRepo.findById(7L)).thenReturn(Optional.of(claim));

        String out = execTool(a, "update_condition",
                Map.of("condition_id", 11, "estimated_rating", 70));

        // The number was NOT written — the pipeline owns veteran-visible numbers.
        assertThat(c.getEstimatedRating()).isEqualTo(10);
        // Dirty-marking: carry-forward fingerprints invalidated + re-run queued.
        assertThat(c.getEvidenceFingerprint()).isNull();
        assertThat(c.getLastFullRunAt()).isNull();
        assertThat(claim.getSynthesisNeeded()).isTrue();
        verify(claimRepo).save(claim);
        verify(condRepo).save(c);
        // Honest copy (trust-fix + Increment A): a re-analysis IS queued (re-checks
        // presumptive/gaps), but the rating NUMBER is never promised to change on a
        // bare dispute — the veteran is directed to add evidence. Must NOT over-promise.
        assertThat(out).contains("re-analysis");
        assertThat(out).contains("evidence");
        assertThat(out).doesNotContain("will update");
        assertThat(out).doesNotContain("being corrected");
    }

    @Test
    void updateCondition_withoutRating_doesNotMarkDirty() throws Exception {
        ChatAgent a = agent();
        IdentifiedCondition c = cond(11L, "Tinnitus", 10, "fp-tinnitus");
        when(condRepo.findById(11L)).thenReturn(Optional.of(c));

        String out = execTool(a, "update_condition",
                Map.of("condition_id", 11, "name", "Tinnitus (bilateral)"));

        assertThat(c.getName()).isEqualTo("Tinnitus (bilateral)");
        assertThat(c.getEvidenceFingerprint()).isEqualTo("ev-fp");   // untouched
        verify(claimRepo, never()).save(any());
        assertThat(out).contains("updated");
    }

    // ---- superseded-target rejection (self-correcting) ----

    @Test
    void updateCondition_onSupersededRow_isRejected_withCurrentList() throws Exception {
        ChatAgent a = agent();
        IdentifiedCondition stale = cond(11L, "Tinnitus", 10, "fp-tinnitus");
        stale.setSupersededBy(42L);   // retired by a newer generation
        IdentifiedCondition current = cond(42L, "Tinnitus", 10, "fp-tinnitus");
        when(condRepo.findById(11L)).thenReturn(Optional.of(stale));
        when(condRepo.findByClaimIdAndSupersededByIsNull(7L)).thenReturn(List.of(current));

        String out = execTool(a, "update_condition",
                Map.of("condition_id", 11, "name", "changed"));

        assertThat(out).contains("was updated").contains("#42").contains("current list");
        assertThat(stale.getName()).isEqualTo("Tinnitus");   // no silent stale write
        verify(condRepo, never()).save(any());
    }

    @Test
    void markGapResolved_onSupersededRow_isRejected() throws Exception {
        ChatAgent a = agent();
        IdentifiedCondition stale = cond(11L, "Knee", 10, "fp-knee");
        stale.setSupersededBy(42L);
        stale.setGaps(new java.util.ArrayList<>(List.of(new java.util.LinkedHashMap<>(Map.of("title", "x")))));
        when(condRepo.findById(11L)).thenReturn(Optional.of(stale));
        when(condRepo.findByClaimIdAndSupersededByIsNull(7L)).thenReturn(List.of());

        String out = execTool(a, "mark_gap_resolved",
                Map.of("condition_id", 11, "gap_index", 0));

        assertThat(out).contains("was updated");
        verify(condRepo, never()).save(any());
    }

    // ---- soft delete + undo ----

    @Test
    void deleteCondition_isSoftDelete_withDurableSuppressionRecord() throws Exception {
        ChatAgent a = agent();
        IdentifiedCondition c = cond(11L, "Tinnitus", 10, "fp-tinnitus");
        when(condRepo.findById(11L)).thenReturn(Optional.of(c));

        String out = execTool(a, "delete_condition",
                Map.of("condition_id", 11, "reason", "veteran says it does not apply"));

        // NEVER hard-deletes; the row is hidden behind the sentinel.
        verify(condRepo, never()).deleteById(anyLong());
        verify(condRepo, never()).delete(any());
        assertThat(c.getSupersededBy()).isEqualTo(ChatAgent.SUPPRESSED_MARKER);
        verify(condRepo).save(c);

        // Durable suppression keyed by identity fingerprint — the resurrect blocker.
        ArgumentCaptor<ConditionSuppression> cap = ArgumentCaptor.forClass(ConditionSuppression.class);
        verify(suppressionRepo).save(cap.capture());
        ConditionSuppression s = cap.getValue();
        assertThat(s.getClaimId()).isEqualTo(7L);
        assertThat(s.getConditionId()).isEqualTo(11L);
        assertThat(s.getIdentityFingerprint()).isEqualTo("fp-tinnitus");
        assertThat(s.getReason()).contains("does not apply");
        assertThat(s.getLiftedAt()).isNull();

        // The model is told the undo path so it can relay it to the veteran.
        assertThat(out).contains("restore_condition");
    }

    @Test
    void deleteCondition_onAlreadySuppressedRow_pointsAtRestore() throws Exception {
        ChatAgent a = agent();
        IdentifiedCondition c = cond(11L, "Tinnitus", 10, "fp-tinnitus");
        c.setSupersededBy(ChatAgent.SUPPRESSED_MARKER);
        when(condRepo.findById(11L)).thenReturn(Optional.of(c));
        when(condRepo.findByClaimIdAndSupersededByIsNull(7L)).thenReturn(List.of());

        String out = execTool(a, "delete_condition", Map.of("condition_id", 11));

        assertThat(out).contains("removed earlier").contains("restore_condition");
        verify(suppressionRepo, never()).save(any());
    }

    @Test
    void restoreCondition_liftsSuppression_andUnhidesTheRow() throws Exception {
        ChatAgent a = agent();
        IdentifiedCondition c = cond(11L, "Tinnitus", 10, "fp-tinnitus");
        c.setSupersededBy(ChatAgent.SUPPRESSED_MARKER);
        ConditionSuppression s = new ConditionSuppression();
        s.setId(5L);
        s.setClaimId(7L);
        s.setConditionId(11L);
        s.setIdentityFingerprint("fp-tinnitus");
        s.setCreatedAt(Instant.parse("2026-07-01T00:00:00Z"));
        when(suppressionRepo.findFirstByClaimIdAndConditionIdAndLiftedAtIsNullOrderByIdDesc(7L, 11L))
                .thenReturn(Optional.of(s));
        when(condRepo.findById(11L)).thenReturn(Optional.of(c));
        when(condRepo.findByClaimId(7L)).thenReturn(List.of(c));   // no later-gen copies

        String out = execTool(a, "restore_condition", Map.of("condition_id", 11));

        assertThat(s.getLiftedAt()).isNotNull();
        verify(suppressionRepo).save(s);
        assertThat(c.getSupersededBy()).isNull();   // visible again
        verify(condRepo).save(c);
        assertThat(out).contains("restored");
    }

    @Test
    void restoreCondition_afterReRunReidentifiedIt_newestCopyTakesOver() throws Exception {
        ChatAgent a = agent();
        // Original row, suppressed.
        IdentifiedCondition suppressed = cond(11L, "Tinnitus", 10, "fp-tinnitus");
        suppressed.setSupersededBy(ChatAgent.SUPPRESSED_MARKER);
        // Two later generations re-identified it while hidden (superseded_by null but
        // filtered out by the read-time suppression join).
        IdentifiedCondition hiddenOld = cond(42L, "Tinnitus", 10, "fp-tinnitus");
        IdentifiedCondition hiddenNew = cond(77L, "Tinnitus", 10, "fp-tinnitus");
        ConditionSuppression s = new ConditionSuppression();
        s.setId(5L);
        s.setClaimId(7L);
        s.setConditionId(11L);
        s.setIdentityFingerprint("fp-tinnitus");
        s.setCreatedAt(Instant.parse("2026-07-01T00:00:00Z"));
        when(suppressionRepo.findFirstByClaimIdAndConditionIdAndLiftedAtIsNullOrderByIdDesc(7L, 11L))
                .thenReturn(Optional.of(s));
        when(condRepo.findById(11L)).thenReturn(Optional.of(suppressed));
        when(condRepo.findByClaimId(7L)).thenReturn(List.of(suppressed, hiddenNew, hiddenOld));

        String out = execTool(a, "restore_condition", Map.of("condition_id", 11));

        // The NEWEST re-identified copy becomes the live one; the older hidden copy is
        // retired onto it; the originally-suppressed row stays hidden (no duplicates).
        assertThat(s.getLiftedAt()).isNotNull();
        assertThat(hiddenOld.getSupersededBy()).isEqualTo(77L);
        assertThat(suppressed.getSupersededBy()).isEqualTo(ChatAgent.SUPPRESSED_MARKER);
        assertThat(hiddenNew.getSupersededBy()).isNull();
        assertThat(out).contains("#77").contains("restored");
    }

    @Test
    void restoreCondition_withNothingToUndo_saysSo() throws Exception {
        ChatAgent a = agent();
        when(suppressionRepo.findFirstByClaimIdAndConditionIdAndLiftedAtIsNullOrderByIdDesc(7L, 99L))
                .thenReturn(Optional.empty());

        String out = execTool(a, "restore_condition", Map.of("condition_id", 99));

        assertThat(out).contains("no removal to undo");
        verify(condRepo, never()).save(any());
    }

    // =====================================================================
    // Increment C — request_reanalysis + record_service_fact.
    // =====================================================================

    /** request_reanalysis sets claim.synthesisNeeded, saves the claim, writes NO rating/
     *  condition number, and returns a status that does not over-promise the number. */
    @Test
    void requestReanalysis_setsSynthesisNeeded_savesClaim_touchesNoRating() throws Exception {
        ChatAgent a = agent();
        Claim claim = new Claim();
        claim.setId(7L);
        claim.setUserId(1L);            // caller user 1 is the owner (P1-19 guard)
        claim.setSynthesisNeeded(false);
        when(claimRepo.findById(7L)).thenReturn(Optional.of(claim));

        String out = execTool(a, "request_reanalysis", Map.of("reason", "veteran disputes tinnitus 10%"));

        assertThat(claim.getSynthesisNeeded()).isTrue();
        verify(claimRepo).save(claim);
        // No condition/rating write path was touched by this tool.
        verify(condRepo, never()).save(any());
        // Honest status: a re-analysis is queued, and ratings only change with new evidence —
        // never over-promise the NUMBER.
        assertThat(out).contains("re-analysis");
        assertThat(out).contains("evidence");
        assertThat(out).doesNotContain("will update");
        assertThat(out).doesNotContain("being corrected");
    }

    /** record_service_fact upserts the ServiceProfile with the deployment in the exact
     *  element shape the presumptive engine reads ({"location": ...}) + the exposure as a
     *  plain string, sets synthesisNeeded, and returns a PROVISIONAL/PACT status that never
     *  asserts a granted presumptive. */
    @Test
    void recordServiceFact_upsertsProfile_correctShapes_queuesReanalysis_provisionalCopy() throws Exception {
        ChatAgent a = agent();
        // No existing profile → new row attached to the owning User (user_id insertable=false).
        when(serviceProfileRepo.findByUserId(1L)).thenReturn(Optional.empty());
        when(userRepo.findById(1L)).thenReturn(Optional.of(User.builder().id(1L).build()));
        Claim claim = new Claim();
        claim.setId(7L);
        claim.setUserId(1L);
        claim.setSynthesisNeeded(false);
        when(claimRepo.findById(7L)).thenReturn(Optional.of(claim));

        String out = execTool(a, "record_service_fact",
                Map.of("deployment_location", "Iraq", "exposure", "burn pit"));

        ArgumentCaptor<ServiceProfile> cap = ArgumentCaptor.forClass(ServiceProfile.class);
        verify(serviceProfileRepo).save(cap.capture());
        ServiceProfile saved = cap.getValue();

        // Deployment element shape: List<Map> with {"location": "Iraq"} (engine reads .get("location")).
        assertThat(saved.getDeployments()).hasSize(1);
        assertThat(saved.getDeployments().get(0)).containsEntry("location", "Iraq");
        // Exposure: plain String list, matched verbatim by the engine keyword scorer.
        assertThat(saved.getExposureRisks()).containsExactly("burn pit");
        // The new row carries the owning user relationship (drives the user_id column on insert).
        assertThat(saved.getUser()).isNotNull();
        assertThat(saved.getUser().getId()).isEqualTo(1L);

        // Re-derive is queued so the presumptive engine runs with the new profile.
        assertThat(claim.getSynthesisNeeded()).isTrue();
        verify(claimRepo).save(claim);

        // Status names what was recorded and mentions provisional/PACT WITHOUT asserting granted.
        assertThat(out).contains("Iraq");
        assertThat(out).contains("burn pit");
        assertThat(out.toLowerCase()).contains("provisional");
        assertThat(out).contains("PACT");
        assertThat(out.toLowerCase()).doesNotContain("granted");
        assertThat(out.toLowerCase()).doesNotContain("qualifies you for");
    }

    /** SECURITY: record_service_fact only writes the authenticated OWNER's profile. A
     *  non-owner caller (viewer / X-View-As) is refused by the P1-19 execution-time guard
     *  and NOTHING is written. */
    @Test
    void recordServiceFact_nonOwnerCaller_isRefused_writesNothing() throws Exception {
        ChatAgent a = agent();
        // Claim 7 is owned by user 1; caller is user 2 (a viewer) → not the owner.
        Claim claim = new Claim();
        claim.setId(7L);
        claim.setUserId(1L);
        when(claimRepo.findById(7L)).thenReturn(Optional.of(claim));

        Method m = ChatAgent.class.getDeclaredMethod("executeTool",
                String.class, Map.class, Long.class, Long.class, Long.class);
        m.setAccessible(true);
        String out = (String) m.invoke(a, "record_service_fact",
                Map.of("deployment_location", "Iraq"), 7L, 2L, 100L);  // userId=2 → viewer

        assertThat(out).contains("only available to the claim owner");
        verify(serviceProfileRepo, never()).save(any());
        verify(userRepo, never()).findById(anyLong());
        verify(claimRepo, never()).save(any());
    }

    /** record_service_fact does not clobber an existing branch when branch is omitted;
     *  only the provided fields are written. */
    @Test
    void recordServiceFact_omittedBranch_doesNotClobberExisting() throws Exception {
        ChatAgent a = agent();
        ServiceProfile existing = ServiceProfile.builder()
                .branch("Army")
                .deployments(new java.util.ArrayList<>())
                .exposureRisks(new java.util.ArrayList<>())
                .build();
        when(serviceProfileRepo.findByUserId(1L)).thenReturn(Optional.of(existing));
        Claim claim = new Claim();
        claim.setId(7L);
        claim.setUserId(1L);
        when(claimRepo.findById(7L)).thenReturn(Optional.of(claim));

        // Only MOS provided — branch is omitted and must be preserved.
        String out = execTool(a, "record_service_fact", Map.of("mos", "11B"));

        assertThat(existing.getBranch()).isEqualTo("Army");   // NOT clobbered to null
        assertThat(existing.getMos()).isEqualTo("11B");
        verify(serviceProfileRepo).save(existing);
        // A brand-new-row User lookup must not have happened (we upserted the existing row).
        verify(userRepo, never()).findById(anyLong());
        assertThat(out).contains("MOS 11B");
    }
}
