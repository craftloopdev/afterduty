package com.afterduty.service.extraction;

import com.afterduty.model.*;
import com.afterduty.repository.*;
import com.afterduty.service.llm.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mission 5a — incremental (delta) extraction, flag ON
 * ({@code va-claim.analysis.incremental=true}). Exercises the changed-docs-only
 * fan-out, atom supersede-on-reextract, the extract_key write-after-success
 * contract, and the failed-doc-stays-re-extractable guarantee — all on the
 * single-pass path. Substrate mirrors {@link SinglePassExtractionStateMachineTest}.
 *
 * <p>A re-extraction is triggered the way it happens in production: the claim's
 * {@code extractionState} is reset to {@code NONE} (what PipelineService does on a
 * new/changed upload), then the state machine is advanced again. Whether a given
 * document is re-submitted is decided purely by its {@code extract_key} — so a doc
 * whose file_hash changed (or whose prompt/schema/model version would change) is
 * re-extracted, and an unchanged doc is skipped.
 */
@DataJpaTest
@Import({
        com.afterduty.service.llm.LlmProviderRouterTestConfig.class,
        com.afterduty.service.llm.LlmJobService.class,
        com.afterduty.service.llm.LlmJobSubmitter.class,
        com.afterduty.service.llm.LlmJobPoller.class,
        ExtractionStateMachine.class,
        com.afterduty.service.rag.RagExtractionTestConfig.class,
        com.afterduty.service.DocumentStorageService.class,
        com.afterduty.service.FakeGcsStorageTestConfig.class,
        DiagnosisExtractorService.class,
        MedicationExtractorService.class,
        ServiceRecordExtractorService.class,
        GeminiExtractionService.class,
        EventSegmentationAgent.class,
        EventExtractionAgent.class,
        SinglePassExtractionService.class,
        com.afterduty.config.LlmRoutingProperties.class
})
@TestPropertySource(properties = {
        "va-claim.llm.submitter.batch-size=50",
        "va-claim.llm.submitter.poll-ms=9999999",
        "va-claim.llm.submitter.initial-delay-ms=9999999",
        "va-claim.llm.poller.poll-ms=9999999",
        "va-claim.llm.poller.initial-delay-ms=9999999",
        "va-claim.llm.orphan-deadline-min=30",
        "va-claim.extraction.single-pass=true",
        "va-claim.analysis.incremental=true"
})
class IncrementalExtractionStateMachineTest {

    static final String DOC_FACTS_A = """
            {
              "doc_type": "health_summary", "doc_date": "2023-06-01",
              "unreadable_or_unsupported": false,
              "diagnoses_not_found": false,
              "diagnoses": [
                {"diagnosis_name": "PTSD", "icd10_code": "F43.10", "status": "chronic"}
              ],
              "medications_not_found": true, "medications": [],
              "service_record_not_found": true,
              "events_not_found": true, "events": [],
              "atoms_not_found": false,
              "atoms": [
                {"type": "symptom", "value": "Nightmares 4x/week", "source": "note", "confidence": 0.9}
              ]
            }
            """;

    static final String DOC_FACTS_B = """
            {
              "doc_type": "health_summary", "doc_date": "2024-01-01",
              "unreadable_or_unsupported": false,
              "diagnoses_not_found": false,
              "diagnoses": [
                {"diagnosis_name": "Tinnitus", "icd10_code": "H93.13", "status": "chronic"}
              ],
              "medications_not_found": true, "medications": [],
              "service_record_not_found": true,
              "events_not_found": true, "events": [],
              "atoms_not_found": true, "atoms": []
            }
            """;

    // A's RE-extraction after the file changed — different diagnosis to prove the
    // live reader sees the NEW set, not the old.
    static final String DOC_FACTS_A_V2 = """
            {
              "doc_type": "health_summary", "doc_date": "2023-06-01",
              "unreadable_or_unsupported": false,
              "diagnoses_not_found": false,
              "diagnoses": [
                {"diagnosis_name": "Major Depressive Disorder", "icd10_code": "F33.1", "status": "chronic"}
              ],
              "medications_not_found": true, "medications": [],
              "service_record_not_found": true,
              "events_not_found": true, "events": [],
              "atoms_not_found": false,
              "atoms": [
                {"type": "symptom", "value": "Low mood daily", "source": "note", "confidence": 0.9}
              ]
            }
            """;

    @Autowired ExtractionStateMachine extractionStateMachine;
    @Autowired LlmJobSubmitter llmJobSubmitter;
    @Autowired LlmJobPoller llmJobPoller;
    @Autowired ClaimRepository claimRepository;
    @Autowired EvidenceItemRepository evidenceItemRepository;
    @Autowired AtomRepository atomRepository;
    @Autowired ClaimPipelineJobRepository pipelineJobRepository;
    @Autowired FakeLlmAsyncProvider fakeProvider;

    private Claim claim;
    private EvidenceItem docA;

    @BeforeEach
    void setUp() {
        fakeProvider.reset();
        claim = claimRepository.save(Claim.builder()
                .userId(7L).status(Claim.ClaimStatus.EXTRACTING).build());
        docA = evidenceItemRepository.save(EvidenceItem.builder()
                .claimId(claim.getId()).sourceType("pdf")
                .rawContent("VA exam content A").filename("docA.pdf")
                .fileHash("hash-A-v1")
                .processingStatus("pending").build());
    }

    /** Run extraction to terminal completion (submit → poll → parse → COMPLETE). */
    private void runExtractionToCompletion() {
        extractionStateMachine.advance(reload());   // NONE → SINGLE_PASS_EXTRACTION (or complete-on-skip)
        llmJobSubmitter.tick();
        llmJobPoller.tick();
        extractionStateMachine.advance(reload());    // parse + → COMPLETE
    }

    private Claim reload() {
        return claimRepository.findById(claim.getId()).orElseThrow();
    }

    /** Reset state to NONE the way a new/changed upload does, then re-run. */
    private void retrigger() {
        Claim c = reload();
        c.setExtractionState("NONE");
        claimRepository.save(c);
    }

    // -------------------------------------------------------------------------
    // extract_key is written after a successful extraction.
    // -------------------------------------------------------------------------

    @Test
    void firstExtraction_writesExtractKey() {
        fakeProvider.setResponseForEvidence(docA.getId(), DOC_FACTS_A);
        runExtractionToCompletion();

        EvidenceItem a = evidenceItemRepository.findById(docA.getId()).orElseThrow();
        assertEquals("processed", a.getProcessingStatus());
        assertNotNull(a.getExtractKey(),
                "A successfully extracted document must have its extract_key written");
        assertEquals(1, atomRepository.findByClaimIdAndSupersededByIsNull(claim.getId()).stream()
                        .filter(at -> "ai:extraction-diagnosis".equals(at.getCreatedBy())).count(),
                "Exactly one diagnosis atom from doc A");
    }

    // -------------------------------------------------------------------------
    // Re-upload a NEW doc: only the new doc gets a job; old doc's atoms untouched.
    // -------------------------------------------------------------------------

    @Test
    void reUploadNewDoc_onlyNewDocGetsJob_oldAtomsUntouched() {
        // Round 1: extract doc A.
        fakeProvider.setResponseForEvidence(docA.getId(), DOC_FACTS_A);
        runExtractionToCompletion();
        List<Atom> aAtomsBefore = atomRepository.findByEvidenceIdAndSupersededByIsNullOrderByCreatedAtAsc(docA.getId());
        assertFalse(aAtomsBefore.isEmpty());
        Long aAtomId = aAtomsBefore.get(0).getId();
        String aKey = evidenceItemRepository.findById(docA.getId()).orElseThrow().getExtractKey();

        // Round 2: add a NEW doc B and re-trigger extraction.
        EvidenceItem docB = evidenceItemRepository.save(EvidenceItem.builder()
                .claimId(claim.getId()).sourceType("pdf")
                .rawContent("Audiology report B").filename("docB.pdf")
                .fileHash("hash-B-v1").processingStatus("pending").build());
        fakeProvider.setResponseForEvidence(docB.getId(), DOC_FACTS_B);
        fakeProvider.reset(); // clear submitted-jobs log; re-set B's response
        fakeProvider.setResponseForEvidence(docB.getId(), DOC_FACTS_B);

        retrigger();
        extractionStateMachine.advance(reload());   // NONE → SINGLE_PASS — should submit ONLY doc B

        // Exactly ONE job this round, and it is for doc B.
        List<ClaimPipelineJob> round2Jobs = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "SINGLE_PASS_EXTRACTION");
        assertEquals(1, round2Jobs.size(), "Only the new/changed doc B should get a job");
        assertEquals(docB.getId(), round2Jobs.get(0).getEvidenceId(),
                "The single round-2 job must be for the new doc B, not the unchanged doc A");

        llmJobSubmitter.tick();
        llmJobPoller.tick();
        extractionStateMachine.advance(reload());   // parse doc B → COMPLETE

        // Doc A's atoms are byte-identical (same row id, never superseded).
        List<Atom> aAtomsAfter = atomRepository.findByEvidenceIdAndSupersededByIsNullOrderByCreatedAtAsc(docA.getId());
        assertEquals(aAtomsBefore.size(), aAtomsAfter.size(),
                "Unchanged doc A must keep exactly its prior atoms");
        assertEquals(aAtomId, aAtomsAfter.get(0).getId(),
                "Doc A's atom row must be the SAME row (not re-created)");
        assertEquals(aKey, evidenceItemRepository.findById(docA.getId()).orElseThrow().getExtractKey(),
                "Doc A's extract_key must be unchanged");

        // Doc B now has its atoms; B got a key.
        EvidenceItem b = evidenceItemRepository.findById(docB.getId()).orElseThrow();
        assertEquals("processed", b.getProcessingStatus());
        assertNotNull(b.getExtractKey());
        assertTrue(atomRepository.findByEvidenceIdAndSupersededByIsNullOrderByCreatedAtAsc(docB.getId())
                        .stream().anyMatch(at -> at.getValue().contains("Tinnitus")),
                "Doc B's Tinnitus diagnosis must be persisted");
    }

    // -------------------------------------------------------------------------
    // Re-extract after a key change (file changed / prompt-or-schema-or-model bump):
    // old atoms superseded NOT duplicated; live readers see exactly the new set.
    // -------------------------------------------------------------------------

    @Test
    void reExtractAfterKeyChange_oldAtomsSuperseded_notDuplicated() {
        // Round 1: extract doc A (PTSD).
        fakeProvider.setResponseForEvidence(docA.getId(), DOC_FACTS_A);
        runExtractionToCompletion();
        long liveAfterR1 = atomRepository.countByClaimIdAndSupersededByIsNull(claim.getId());
        assertTrue(liveAfterR1 > 0);
        assertTrue(atomRepository.findByClaimIdAndSupersededByIsNull(claim.getId()).stream()
                        .anyMatch(at -> at.getValue().contains("PTSD")),
                "Round 1 live atoms include PTSD");

        // The file changes (new bytes ⇒ new file_hash ⇒ extract_key mismatch). This
        // is the exact mechanism by which a PROMPT_VERSION/SCHEMA_VERSION/model bump
        // also forces re-extraction (all manifest as a stored-vs-computed key mismatch).
        EvidenceItem a = evidenceItemRepository.findById(docA.getId()).orElseThrow();
        a.setFileHash("hash-A-v2");
        evidenceItemRepository.save(a);

        fakeProvider.reset();
        fakeProvider.setResponseForEvidence(docA.getId(), DOC_FACTS_A_V2);

        retrigger();
        extractionStateMachine.advance(reload());   // should re-submit doc A (key mismatch)
        List<ClaimPipelineJob> r2 = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "SINGLE_PASS_EXTRACTION");
        assertEquals(1, r2.size(), "Changed doc A must be re-submitted");
        assertEquals(docA.getId(), r2.get(0).getEvidenceId());

        llmJobSubmitter.tick();
        llmJobPoller.tick();
        extractionStateMachine.advance(reload());   // supersede old A atoms, persist new, → COMPLETE

        // Live readers see EXACTLY the new set — MDD present, PTSD gone from live.
        List<Atom> live = atomRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
        assertTrue(live.stream().anyMatch(at -> at.getValue().contains("Major Depressive Disorder")),
                "Live set must contain the re-extracted MDD diagnosis");
        assertFalse(live.stream().anyMatch(at -> at.getValue().contains("PTSD")),
                "The prior PTSD atom must NOT appear in the live set (superseded)");

        // No duplication: the live diagnosis count for doc A is exactly 1.
        long liveDiag = atomRepository.findByEvidenceIdAndSupersededByIsNullOrderByCreatedAtAsc(docA.getId())
                .stream().filter(at -> "ai:extraction-diagnosis".equals(at.getCreatedBy())).count();
        assertEquals(1, liveDiag, "Re-extraction must not duplicate atoms — exactly one live diagnosis");

        // The old atoms still EXIST (kept for citation history) but are superseded.
        long superseded = atomRepository.findByEvidenceIdOrderByCreatedAtAsc(docA.getId()).stream()
                .filter(at -> at.getSupersededBy() != null).count();
        assertTrue(superseded > 0, "Prior atoms are retained as superseded, not deleted");
        // The supersede marker is the re-extracted document's own id (provenance).
        assertTrue(atomRepository.findByEvidenceIdOrderByCreatedAtAsc(docA.getId()).stream()
                        .filter(at -> at.getSupersededBy() != null)
                        .allMatch(at -> docA.getId().equals(at.getSupersededBy())),
                "Superseded atoms must carry the re-extracted evidence id as the marker");
    }

    // -------------------------------------------------------------------------
    // A failed (unparseable) doc keeps NO extract_key, so it retries next round.
    // -------------------------------------------------------------------------

    @Test
    void failedDoc_keepsNoExtractKey_retriesNextRound() {
        // Round 1: doc A returns garbage → parse fails → no key written.
        fakeProvider.setResponseForEvidence(docA.getId(), "not valid DocFacts json");
        runExtractionToCompletion();

        EvidenceItem a1 = evidenceItemRepository.findById(docA.getId()).orElseThrow();
        assertEquals("error", a1.getProcessingStatus(),
                "An unparseable single-doc extraction fails that doc");
        assertNull(a1.getExtractKey(),
                "A failed document must NOT have an extract_key written (stays re-extractable)");

        // Round 2: same doc, now returns valid DocFacts → it must be re-submitted.
        fakeProvider.reset();
        fakeProvider.setResponseForEvidence(docA.getId(), DOC_FACTS_A);

        retrigger();
        extractionStateMachine.advance(reload());
        List<ClaimPipelineJob> r2 = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "SINGLE_PASS_EXTRACTION");
        assertEquals(1, r2.size(), "A previously-failed doc (null key) must be retried");
        assertEquals(docA.getId(), r2.get(0).getEvidenceId());

        llmJobSubmitter.tick();
        llmJobPoller.tick();
        extractionStateMachine.advance(reload());

        EvidenceItem a2 = evidenceItemRepository.findById(docA.getId()).orElseThrow();
        assertEquals("processed", a2.getProcessingStatus(), "The retry succeeds");
        assertNotNull(a2.getExtractKey(), "A successful retry writes the extract_key");
        assertTrue(atomRepository.findByClaimIdAndSupersededByIsNull(claim.getId()).stream()
                        .anyMatch(at -> at.getValue().contains("PTSD")),
                "The retry's atoms are now live");
    }

    // -------------------------------------------------------------------------
    // No-op re-trigger: all docs unchanged ⇒ zero jobs, stage completes cleanly,
    // atoms untouched (the headline incremental win).
    // -------------------------------------------------------------------------

    @Test
    void allDocsUnchanged_completesWithNoJobs_atomsUntouched() {
        fakeProvider.setResponseForEvidence(docA.getId(), DOC_FACTS_A);
        runExtractionToCompletion();
        long liveBefore = atomRepository.countByClaimIdAndSupersededByIsNull(claim.getId());

        fakeProvider.reset(); // no responses set — if a job were submitted it would have no canned reply

        retrigger();
        extractionStateMachine.advance(reload());   // all unchanged → skip all → complete

        // No new jobs submitted; stage completed (extractionState null).
        List<ClaimPipelineJob> jobs = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "SINGLE_PASS_EXTRACTION");
        assertTrue(jobs.isEmpty(), "An all-unchanged re-trigger must submit zero extraction jobs");
        assertNull(reload().getExtractionState(),
                "An all-unchanged re-trigger must complete the stage cleanly (not wedge, not fail)");
        assertNotEquals(Claim.ClaimStatus.ERROR, reload().getStatus(),
                "An all-unchanged re-trigger is a success, not a failure");

        // Atoms untouched.
        assertEquals(liveBefore, atomRepository.countByClaimIdAndSupersededByIsNull(claim.getId()),
                "Unchanged docs must leave the live atom set exactly as it was");
    }
}
