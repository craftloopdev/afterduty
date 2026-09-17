package com.afterduty.service;

import com.afterduty.model.Claim;
import com.afterduty.model.EvidenceItem;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.ConditionRepository;
import com.afterduty.repository.ConditionSuppressionRepository;
import com.afterduty.repository.EvidenceRepository;
import com.afterduty.repository.MessageRepository;
import com.afterduty.repository.VasrdRecordRepository;
import com.afterduty.service.rag.HybridRetrievalService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * P1-12 — the deterministic claim-state block must carry the SAME detail the UI's
 * "Why this number?" panel and gap cards show (rating rationale, per-leg triad with
 * cited evidence, gap how-tos), so the model can never contradict the screen next to
 * it. Also: the block sits inside the cached system prefix (Mission 6b), so the new
 * fields must be byte-stable across renders of unchanged state; and the new read-only
 * {@code get_pipeline_status} tool renders the /jobs data so "my new doc changed
 * nothing, why?" is answerable.
 */
@Tag("regression")
class ChatAgentClaimStateGroundingTest {

    private final AtomRepository atomRepo = Mockito.mock(AtomRepository.class);
    private final ConditionRepository condRepo = Mockito.mock(ConditionRepository.class);
    private final EvidenceRepository evidenceRepo = Mockito.mock(EvidenceRepository.class);
    private final ClaimRepository claimRepo = Mockito.mock(ClaimRepository.class);

    private ChatAgent agent() {
        return new ChatAgent(atomRepo, condRepo,
                Mockito.mock(MessageRepository.class), Mockito.mock(AiCostService.class),
                Mockito.mock(HybridRetrievalService.class),
                Mockito.mock(VasrdRecordRepository.class),
                Mockito.mock(VasrdDataService.class),
                evidenceRepo, claimRepo,
                Mockito.mock(ConditionSuppressionRepository.class),
                Mockito.mock(com.afterduty.repository.ServiceProfileRepository.class),
                Mockito.mock(com.afterduty.repository.UserRepository.class));
    }

    /** A condition with the full detail the UI shows: rationale, triad legs, gap how-to. */
    private IdentifiedCondition richCondition() {
        IdentifiedCondition c = new IdentifiedCondition();
        c.setId(11L);
        c.setClaimId(7L);
        c.setName("Right knee strain");
        c.setVasrdCode("5260");
        c.setEstimatedRating(10);
        c.setRatingRationale("Flexion limited to 45 degrees supports 10% under DC 5260.");
        c.setTriadDiagnosis(new LinkedHashMap<>(Map.of(
                "status", "STRONG",
                "evidence", List.of("2019 C&P exam diagnosis", "MRI 2020"))));
        c.setTriadInService(new LinkedHashMap<>(Map.of(
                "status", "MODERATE",
                "evidence", List.of("2015 airborne injury STR entry"))));
        c.setTriadNexus(new LinkedHashMap<>(Map.of("status", "MISSING")));
        List<Map<String, Object>> gaps = new ArrayList<>();
        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("title", "Nexus opinion");
        gap.put("type", "nexus_letter");
        gap.put("triad_leg", "nexus");
        gap.put("priority", "high");
        gap.put("description", "A medical opinion linking the knee condition to service.");
        gap.put("how_to_get_it", "Ask your treating provider for a nexus letter using the 'at least as likely as not' language.");
        gap.put("impact", "Establishes the service-connection leg VA requires.");
        gap.put("status", "open");
        gaps.add(gap);
        c.setGaps(gaps);
        return c;
    }

    // ---- the grounding block carries the UI's detail ----

    @Test
    void claimStateBlock_containsRationale_triadLegs_andGapHowTos() {
        ChatAgent a = agent();
        when(atomRepo.findByClaimIdAndSupersededByIsNull(anyLong())).thenReturn(List.of());
        when(condRepo.findByClaimIdAndSupersededByIsNull(anyLong())).thenReturn(List.of(richCondition()));

        String block = a.buildClaimStateBlock(7L);

        // Rating rationale — the "Why this number?" panel's content.
        assertThat(block).contains("Why this rating:")
                .contains("Flexion limited to 45 degrees supports 10% under DC 5260.");
        // Per-leg triad with status AND the cited evidence.
        assertThat(block).contains("Service-connection triad:")
                .contains("diagnosis: STRONG — evidence: 2019 C&P exam diagnosis; MRI 2020")
                .contains("in_service: MODERATE — evidence: 2015 airborne injury STR entry")
                .contains("nexus: MISSING");
        // Gap detail: leg, priority, what's needed, and the how-to the paid UI renders.
        assertThat(block).contains("(leg: nexus)").contains("(priority: high)")
                .contains("What's needed: A medical opinion linking the knee condition to service.")
                .contains("How to get it: Ask your treating provider for a nexus letter")
                .contains("Impact: Establishes the service-connection leg VA requires.");
    }

    @Test
    void claimStateBlock_isByteStable_acrossTwoRendersOfUnchangedState() {
        ChatAgent a = agent();
        when(atomRepo.findByClaimIdAndSupersededByIsNull(anyLong())).thenReturn(List.of());
        // Fresh (equal-valued) object graphs per call — like two repository reads.
        when(condRepo.findByClaimIdAndSupersededByIsNull(anyLong()))
                .thenReturn(List.of(richCondition()))
                .thenReturn(List.of(richCondition()));

        String first = a.buildClaimStateBlock(7L);
        String second = a.buildClaimStateBlock(7L);

        // The block is inside the cached system prefix: any byte drift between renders
        // of UNCHANGED state silently re-pays the full cache write every turn.
        assertThat(second).isEqualTo(first);
    }

    @Test
    void claimStateBlock_skipsAbsentDetail_withoutPlaceholders() {
        ChatAgent a = agent();
        IdentifiedCondition bare = new IdentifiedCondition();
        bare.setId(12L);
        bare.setClaimId(7L);
        bare.setName("Tinnitus");
        when(atomRepo.findByClaimIdAndSupersededByIsNull(anyLong())).thenReturn(List.of());
        when(condRepo.findByClaimIdAndSupersededByIsNull(anyLong())).thenReturn(List.of(bare));

        String block = a.buildClaimStateBlock(7L);

        assertThat(block).contains("Tinnitus");
        assertThat(block).doesNotContain("Why this rating:")
                .doesNotContain("Service-connection triad:")
                .doesNotContain("null");
    }

    // ---- get_pipeline_status (read-only /jobs render) ----

    private String execTool(ChatAgent a, String name, Map<String, Object> input) throws Exception {
        Method m = ChatAgent.class.getDeclaredMethod("executeTool",
                String.class, Map.class, Long.class, Long.class, Long.class);
        m.setAccessible(true);
        return (String) m.invoke(a, name, input, 7L, 1L, 100L);
    }

    @Test
    void getPipelineStatus_rendersDocStatuses_runStates_andQueuedReanalysis() throws Exception {
        ChatAgent a = agent();
        Claim claim = new Claim();
        claim.setId(7L);
        claim.setStatus(Claim.ClaimStatus.ANALYZED);
        claim.setSynthesisNeeded(true);
        claim.setSynthesisInProgress(false);
        claim.setLastSynthesisAt(Instant.parse("2026-07-01T10:00:00Z"));
        claim.setGapAnalysisInProgress(true);
        when(claimRepo.findById(7L)).thenReturn(Optional.of(claim));

        EvidenceItem done = new EvidenceItem();
        done.setFilename("dd214.pdf");
        done.setProcessingStatus("processed");
        EvidenceItem waiting = new EvidenceItem();
        waiting.setFilename("str-photo.jpg");
        waiting.setProcessingStatus("queued");
        when(evidenceRepo.findByClaimIdOrderByCreatedAt(7L)).thenReturn(List.of(done, waiting));

        IdentifiedCondition pendingGaps = new IdentifiedCondition();
        pendingGaps.setId(11L);
        pendingGaps.setClaimId(7L);
        pendingGaps.setName("Tinnitus");
        pendingGaps.setGaps(null);   // P0-5: generation flipped, gap analysis not landed
        when(condRepo.findByClaimIdAndSupersededByIsNull(7L)).thenReturn(List.of(pendingGaps));
        when(atomRepo.countByClaimIdAndSupersededByIsNull(7L)).thenReturn(9L);

        String out = execTool(a, "get_pipeline_status", Map.of());

        assertThat(out).contains("Claim status: ANALYZED");
        assertThat(out).contains("dd214.pdf: processed").contains("str-photo.jpg: queued");
        assertThat(out).contains("1 queued").contains("1 processed");
        assertThat(out).contains("Condition analysis (synthesis): idle")
                .contains("last completed 2026-07-01T10:00:00Z");
        assertThat(out).contains("Gap analysis: running");
        assertThat(out).contains("has not landed yet");
        assertThat(out).contains("Re-analysis queued (new facts waiting to be analyzed): yes");
        assertThat(out).contains("9 facts on file").contains("1 conditions");
    }

    @Test
    void getPipelineStatus_isRegisteredInFrozenInstructions() {
        // The model only calls tools it is told about; the frozen block must teach it.
        assertThat(ChatAgent.FROZEN_INSTRUCTIONS).contains("get_pipeline_status");
    }
}
