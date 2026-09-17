package com.afterduty.service.synthesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.Claim;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.model.Notification;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.IdentifiedConditionRepository;
import com.afterduty.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Phase B item B1 — deterministic diff-at-flip → {@link Notification} rows.
 * {@link ConditionGenerationService#supersedePriorGeneration} is the flip (it runs
 * inside the same transaction that activates the new generation), so the diff and
 * its notification writes hang off it. Pure unit tests: repositories are Mockito
 * mocks, the saved rows are captured and asserted verbatim — every veteran-visible
 * string here is a template over the deterministic diff (kill-list rule: no LLM
 * writes any number or diff text).
 */
class GenerationFlipNotificationTest {

    private static final long CLAIM_ID = 42L;
    private static final long USER_ID = 7L;

    private final ObjectMapper mapper = new ObjectMapper();

    private IdentifiedConditionRepository conditionRepository;
    private NotificationRepository notificationRepository;
    private ClaimRepository claimRepository;
    private ConditionGenerationService svc;

    @BeforeEach
    void setUp() {
        conditionRepository = mock(IdentifiedConditionRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        claimRepository = mock(ClaimRepository.class);
        svc = new ConditionGenerationService(conditionRepository);
        svc.setNotificationRepository(notificationRepository);
        svc.setClaimRepository(claimRepository);

        Claim claim = new Claim();
        claim.setUserId(USER_ID);
        when(claimRepository.findById(CLAIM_ID)).thenReturn(Optional.of(claim));
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    /** A condition with a stamped identity fingerprint (same name ⇒ same identity). */
    private IdentifiedCondition cond(Long id, String name, Integer rating) {
        IdentifiedCondition c = new IdentifiedCondition();
        c.setId(id);
        c.setClaimId(CLAIM_ID);
        c.setName(name);
        c.setEstimatedRating(rating);
        c.setIdentityFingerprint(svc.computeIdentityFingerprint(c));
        return c;
    }

    private static Map<String, Object> leg(String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        return m;
    }

    private static Map<String, Object> gap(String type, String triadLeg, String status) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("type", type);
        g.put("triad_leg", triadLeg);
        if (status != null) g.put("status", status);
        return g;
    }

    private List<Notification> savedNotifications() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    private Map<String, Object> metadata(Notification n) throws Exception {
        return mapper.readValue(n.getMetadataJson(), Map.class);
    }

    // -------------------------------------------------------------------------
    // Rating deltas
    // -------------------------------------------------------------------------

    @Test
    void ratingUp_writesGranularSuccessRowAndDigest() throws Exception {
        IdentifiedCondition prior = cond(1L, "PTSD", 50);
        IdentifiedCondition current = cond(11L, "PTSD", 70);

        svc.supersedePriorGeneration(List.of(prior), List.of(current));

        List<Notification> saved = savedNotifications();
        assertEquals(2, saved.size(), "one granular rating row + one digest");

        Notification granular = saved.get(0);
        assertEquals("rating_changed", granular.getEventType());
        assertEquals("success", granular.getSeverity(), "rating up ⇒ success");
        assertEquals(USER_ID, granular.getUserId());
        assertEquals(CLAIM_ID, granular.getClaimId());
        assertEquals(11L, granular.getConditionId(), "granular row anchors to the ACTIVE (new) row");
        assertEquals("PTSD rating estimate changed from 50% to 70%.", granular.getBody());

        Notification digest = saved.get(1);
        assertEquals("analysis_updated", digest.getEventType());
        assertEquals("Your analysis was updated", digest.getTitle());
        assertEquals("Your new evidence updated 1 condition: PTSD rating estimate 50% -> 70%.",
                digest.getBody());

        Map<String, Object> meta = metadata(digest);
        List<Map<String, Object>> ratingChanges = (List<Map<String, Object>>) meta.get("ratingChanges");
        assertEquals(1, ratingChanges.size());
        assertEquals(11, ratingChanges.get(0).get("conditionId"));
        assertEquals("PTSD", ratingChanges.get(0).get("name"));
        assertEquals(50, ratingChanges.get(0).get("from"));
        assertEquals(70, ratingChanges.get(0).get("to"));
        assertEquals(List.of(11), meta.get("changedConditionIds"));
        assertNotNull(meta.get("runId"));

        // The flip itself still happened: old row points at its replacement.
        assertEquals(11L, prior.getSupersededBy());
        verify(conditionRepository).save(prior);
    }

    @Test
    void ratingDown_severityInfo_bodyStatesBothNumbersPlainly() {
        svc.supersedePriorGeneration(
                List.of(cond(1L, "PTSD", 70)),
                List.of(cond(11L, "PTSD", 50)));

        Notification granular = savedNotifications().get(0);
        assertEquals("rating_changed", granular.getEventType());
        assertEquals("info", granular.getSeverity(), "a decrease is stated plainly, never spun");
        assertEquals("PTSD rating estimate changed from 70% to 50%.", granular.getBody());
    }

    // -------------------------------------------------------------------------
    // Triad leg deltas
    // -------------------------------------------------------------------------

    @Test
    void legStrengthened_digestOnly_carriesLegChangeMetadata() throws Exception {
        IdentifiedCondition prior = cond(1L, "Right knee strain", 10);
        prior.setTriadNexus(leg("WEAK"));
        IdentifiedCondition current = cond(11L, "Right knee strain", 10);
        current.setTriadNexus(leg("STRONG"));

        svc.supersedePriorGeneration(List.of(prior), List.of(current));

        List<Notification> saved = savedNotifications();
        assertEquals(1, saved.size(), "leg changes have no granular event type — digest only");

        Notification digest = saved.get(0);
        assertEquals("analysis_updated", digest.getEventType());
        assertEquals("Your new evidence updated 1 condition: Right knee strain nexus evidence strengthened.",
                digest.getBody());

        Map<String, Object> meta = metadata(digest);
        List<Map<String, Object>> legChanges = (List<Map<String, Object>>) meta.get("legChanges");
        assertEquals(1, legChanges.size());
        assertEquals("nx", legChanges.get(0).get("leg"));
        assertEquals("WEAK", legChanges.get(0).get("from"));
        assertEquals("STRONG", legChanges.get(0).get("to"));
    }

    @Test
    void legWeakened_saysWeakened() {
        IdentifiedCondition prior = cond(1L, "PTSD", 50);
        prior.setTriadDiagnosis(leg("STRONG"));
        IdentifiedCondition current = cond(11L, "PTSD", 50);
        current.setTriadDiagnosis(leg("MODERATE"));

        svc.supersedePriorGeneration(List.of(prior), List.of(current));

        assertEquals("Your new evidence updated 1 condition: PTSD diagnosis evidence weakened.",
                savedNotifications().get(0).getBody());
    }

    // -------------------------------------------------------------------------
    // Added / retired
    // -------------------------------------------------------------------------

    @Test
    void addedAndRetired_conditionAddedRowPlusDigestMetadata() throws Exception {
        IdentifiedCondition prior = cond(1L, "PTSD", 50);
        IdentifiedCondition current = cond(11L, "Tinnitus", 10);

        svc.supersedePriorGeneration(List.of(prior), List.of(current));

        List<Notification> saved = savedNotifications();
        assertEquals(2, saved.size());

        Notification added = saved.get(0);
        assertEquals("condition_added", added.getEventType());
        assertEquals("success", added.getSeverity());
        assertEquals(11L, added.getConditionId());
        assertEquals("Tinnitus was added to your claim analysis.", added.getBody());

        Notification digest = saved.get(1);
        assertEquals("Your analysis was updated. 1 new condition found."
                + " 1 condition no longer supported by the evidence.", digest.getBody());
        Map<String, Object> meta = metadata(digest);
        List<Map<String, Object>> addedMeta = (List<Map<String, Object>>) meta.get("addedConditions");
        assertEquals(11, addedMeta.get(0).get("conditionId"));
        assertEquals("Tinnitus", addedMeta.get(0).get("name"));
        List<Map<String, Object>> retiredMeta = (List<Map<String, Object>>) meta.get("retiredConditions");
        assertEquals(1, retiredMeta.size());
        assertEquals("PTSD", retiredMeta.get(0).get("name"));

        // Retire path unchanged: no identity match ⇒ tombstone.
        assertEquals(ConditionGenerationService.TOMBSTONE_MARKER, prior.getSupersededBy());
    }

    // -------------------------------------------------------------------------
    // Gap deltas
    // -------------------------------------------------------------------------

    @Test
    void gapsClosedAndOpened_countedByTypeAndLeg() throws Exception {
        IdentifiedCondition prior = cond(1L, "PTSD", 50);
        prior.setGaps(List.of(gap("nexus_letter", "nexus", null), gap("buddy_statement", null, "open")));
        IdentifiedCondition current = cond(11L, "PTSD", 50);
        current.setGaps(List.of(gap("buddy_statement", null, null), gap("treatment_record", null, null)));

        svc.supersedePriorGeneration(List.of(prior), List.of(current));

        List<Notification> saved = savedNotifications();
        assertEquals(1, saved.size(), "gap-only change ⇒ digest only");
        Notification digest = saved.get(0);
        assertEquals("Your analysis was updated. 1 evidence gap closed. 1 new evidence gap identified.",
                digest.getBody());
        Map<String, Object> meta = metadata(digest);
        assertEquals(1, meta.get("gapsClosed"));
        assertEquals(1, meta.get("gapsOpened"));
        assertEquals(List.of(11), meta.get("changedConditionIds"));
    }

    @Test
    void resolvedGapDisappearing_isNotCountedClosedAgain() {
        // The veteran already resolved it (P1-6 status) — its disappearance is not news.
        IdentifiedCondition prior = cond(1L, "PTSD", 50);
        prior.setGaps(List.of(gap("nexus_letter", "nexus", "resolved")));
        IdentifiedCondition current = cond(11L, "PTSD", 50);
        current.setGaps(List.of());

        svc.supersedePriorGeneration(List.of(prior), List.of(current));

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void nullNewGaps_dirtyConditionAwaitingGapRun_contributesNoGapDelta() {
        // At the synthesis flip a DIRTY condition's gaps are still null (gap fan-out
        // runs after). Treating null as "all closed" would fabricate a diff.
        IdentifiedCondition prior = cond(1L, "PTSD", 50);
        prior.setGaps(List.of(gap("nexus_letter", "nexus", null), gap("buddy_statement", null, null)));
        IdentifiedCondition current = cond(11L, "PTSD", 50);
        current.setGaps(null);

        svc.supersedePriorGeneration(List.of(prior), List.of(current));

        verify(notificationRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Cap, isolation, first analysis, no-op
    // -------------------------------------------------------------------------

    @Test
    void granularRowsCapAtEight_digestMetadataStillCarriesEverything() throws Exception {
        List<IdentifiedCondition> prior = new ArrayList<>();
        List<IdentifiedCondition> current = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            prior.add(cond((long) (i + 1), "Condition " + i, 10));
            current.add(cond((long) (i + 101), "Condition " + i, 30));
        }

        svc.supersedePriorGeneration(prior, current);

        List<Notification> saved = savedNotifications();
        assertEquals(9, saved.size(), "8 granular (capped) + 1 digest");
        for (int i = 0; i < 8; i++) {
            assertEquals("rating_changed", saved.get(i).getEventType());
        }
        Notification digest = saved.get(8);
        assertEquals("analysis_updated", digest.getEventType());
        List<Map<String, Object>> ratingChanges =
                (List<Map<String, Object>>) metadata(digest).get("ratingChanges");
        assertEquals(10, ratingChanges.size(), "digest metadata is uncapped");
    }

    @Test
    void notificationWriteFailure_neverBlocksTheFlip() {
        when(notificationRepository.save(any())).thenThrow(new RuntimeException("insert failed"));
        IdentifiedCondition prior = cond(1L, "PTSD", 50);
        IdentifiedCondition current = cond(11L, "PTSD", 70);

        assertDoesNotThrow(() -> svc.supersedePriorGeneration(List.of(prior), List.of(current)));

        // The supersede still ran to completion.
        assertEquals(11L, prior.getSupersededBy());
        verify(conditionRepository).save(prior);
    }

    @Test
    void diffFailure_neverBlocksTheFlip() {
        // Even resolving the user can blow up — the flip must not care.
        when(claimRepository.findById(anyLong())).thenThrow(new RuntimeException("db down"));
        IdentifiedCondition prior = cond(1L, "PTSD", 50);
        IdentifiedCondition current = cond(11L, "PTSD", 70);

        assertDoesNotThrow(() -> svc.supersedePriorGeneration(List.of(prior), List.of(current)));

        assertEquals(11L, prior.getSupersededBy());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void firstAnalysis_writesAnalysisCompleteInsteadOfDiff() throws Exception {
        svc.supersedePriorGeneration(List.of(),
                List.of(cond(11L, "PTSD", 50), cond(12L, "Tinnitus", 10)));

        List<Notification> saved = savedNotifications();
        assertEquals(1, saved.size());
        Notification n = saved.get(0);
        assertEquals("analysis_complete", n.getEventType());
        assertEquals("success", n.getSeverity());
        assertEquals("We found 2 conditions in your records.", n.getBody());
        assertEquals(USER_ID, n.getUserId());
        assertEquals(CLAIM_ID, n.getClaimId());
        assertEquals(2, metadata(n).get("conditionCount"));
        // First-run metadata lists every found condition under the digest
        // schema's addedConditions key, so the card/timeline render the found
        // list through the same code path as an update's diff.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> found = (List<Map<String, Object>>) metadata(n).get("addedConditions");
        assertEquals(2, found.size());
        assertEquals(11, found.get(0).get("conditionId"));
        assertEquals("PTSD", found.get(0).get("name"));
        assertEquals("Tinnitus", found.get(1).get("name"));
    }

    @Test
    void identicalGenerations_writeNothing() {
        IdentifiedCondition prior = cond(1L, "PTSD", 50);
        prior.setTriadNexus(leg("STRONG"));
        prior.setGaps(List.of(gap("buddy_statement", null, null)));
        IdentifiedCondition current = cond(11L, "PTSD", 50);
        current.setTriadNexus(leg("STRONG"));
        current.setGaps(List.of(gap("buddy_statement", null, null)));

        svc.supersedePriorGeneration(List.of(prior), List.of(current));

        verify(notificationRepository, never()).save(any());
        // The flip itself still points old → new.
        assertEquals(11L, prior.getSupersededBy());
    }

    @Test
    void emptyToEmpty_writesNothing() {
        svc.supersedePriorGeneration(List.of(), List.of());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void repositoriesNotWired_flipStillWorks() {
        // Test slices construct this service without the optional deps.
        ConditionGenerationService bare = new ConditionGenerationService(conditionRepository);
        IdentifiedCondition prior = cond(1L, "PTSD", 50);
        IdentifiedCondition current = cond(11L, "PTSD", 70);

        assertDoesNotThrow(() -> bare.supersedePriorGeneration(List.of(prior), List.of(current)));
        assertEquals(11L, prior.getSupersededBy());
    }
}
