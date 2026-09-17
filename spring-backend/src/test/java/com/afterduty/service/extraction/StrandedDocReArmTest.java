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
 * P0-6 — a document uploaded while an extraction stage is already in flight
 * joins no stage: PipelineService only arms extractionState when it is null,
 * and transitionTo(COMPLETE) used to null it unconditionally, so the machine
 * went quiet and the stranded doc was NEVER extracted (synthesis then ran
 * without its facts). The fix: at the COMPLETE transition, if any doc still
 * lacks a current extract_key (and is not in a terminal per-doc failure state),
 * re-arm to NONE so the scheduler re-drives extraction — no third upload
 * required. Substrate mirrors {@link IncrementalExtractionStateMachineTest}.
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
class StrandedDocReArmTest {

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
              "atoms_not_found": true, "atoms": []
            }
            """;

    static final String DOC_FACTS_B = """
            {
              "doc_type": "nexus_letter", "doc_date": "2024-01-01",
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
        Claim c = Claim.builder()
                .userId(7L).status(Claim.ClaimStatus.EXTRACTING).build();
        c.setExtractionState("NONE");
        claim = claimRepository.save(c);
        docA = evidenceItemRepository.save(EvidenceItem.builder()
                .claimId(claim.getId()).sourceType("pdf")
                .rawContent("VA exam content A").filename("docA.pdf")
                .fileHash("hash-A-v1")
                .processingStatus("queued").build());
    }

    private Claim reload() {
        return claimRepository.findById(claim.getId()).orElseThrow();
    }

    /**
     * "Upload the nexus letter, then remember the C&P results a minute later":
     * doc B arrives while doc A's stage is in flight. When A's stage completes,
     * the machine must re-arm (extractionState NONE, not null) and the very next
     * scheduler ticks must extract B — with NO further upload.
     */
    @Test
    void docUploadedMidStage_isExtractedWithoutAThirdUpload() {
        fakeProvider.setResponseForEvidence(docA.getId(), DOC_FACTS_A);

        // Stage starts with doc A only.
        extractionStateMachine.advance(reload());   // NONE → SINGLE_PASS_EXTRACTION
        assertEquals("SINGLE_PASS_EXTRACTION", reload().getExtractionState());

        // Mid-stage: doc B is uploaded. This is exactly what PipelineService
        // leaves behind — a queued row, and NO extractionState re-arm because
        // the state is non-null (the `if (extractionState == null)` no-op).
        EvidenceItem docB = evidenceItemRepository.save(EvidenceItem.builder()
                .claimId(claim.getId()).sourceType("pdf")
                .rawContent("C&P exam results B").filename("docB.pdf")
                .fileHash("hash-B-v1").processingStatus("queued").build());
        fakeProvider.setResponseForEvidence(docB.getId(), DOC_FACTS_B);

        // Doc A's job completes; the machine parses it and reaches COMPLETE.
        llmJobSubmitter.tick();
        llmJobPoller.tick();
        extractionStateMachine.advance(reload());

        // The regression: extractionState used to be nulled here, stranding B
        // forever. It must instead be re-armed to NONE.
        assertEquals("NONE", reload().getExtractionState(),
                "COMPLETE with an unextracted doc must re-arm to NONE, not go quiet");
        assertEquals("processed",
                evidenceItemRepository.findById(docA.getId()).orElseThrow().getProcessingStatus(),
                "Doc A finished normally");

        // Next scheduler ticks: ONLY doc B is submitted (A's key is current).
        extractionStateMachine.advance(reload());   // NONE → SINGLE_PASS_EXTRACTION (doc B)
        List<ClaimPipelineJob> rearmJobs = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "SINGLE_PASS_EXTRACTION");
        assertEquals(1, rearmJobs.size(), "The re-armed run must submit exactly the stranded doc");
        assertEquals(docB.getId(), rearmJobs.get(0).getEvidenceId());

        llmJobSubmitter.tick();
        llmJobPoller.tick();
        extractionStateMachine.advance(reload());   // parse B → COMPLETE (no stranded docs left)

        // Terminal: B extracted, machine done for real this time.
        EvidenceItem b = evidenceItemRepository.findById(docB.getId()).orElseThrow();
        assertEquals("processed", b.getProcessingStatus(), "The stranded doc B must be extracted");
        assertNotNull(b.getExtractKey(), "B's successful extraction writes its key");
        assertTrue(atomRepository.findByClaimIdAndSupersededByIsNull(claim.getId()).stream()
                        .anyMatch(at -> at.getValue().contains("Tinnitus")),
                "B's facts must be live for synthesis");
        assertNull(reload().getExtractionState(),
                "With every doc keyed, COMPLETE must terminate (no re-arm loop)");
        assertNotEquals(Claim.ClaimStatus.ERROR, reload().getStatus());
    }

    /**
     * Loop guard: a doc in a terminal per-doc failure state (error) must NOT
     * re-arm the machine — it stays re-extractable via the next upload or
     * reprocess, but an unreadable doc must never spin the scheduler forever.
     */
    @Test
    void errorDoc_doesNotReArm() {
        fakeProvider.setResponseForEvidence(docA.getId(), DOC_FACTS_A);
        extractionStateMachine.advance(reload());

        EvidenceItem docB = evidenceItemRepository.save(EvidenceItem.builder()
                .claimId(claim.getId()).sourceType("pdf")
                .rawContent("garbage").filename("docB.pdf")
                .fileHash("hash-B-v1")
                .processingStatus("error").build());

        llmJobSubmitter.tick();
        llmJobPoller.tick();
        extractionStateMachine.advance(reload());

        assertNull(reload().getExtractionState(),
                "An error-state doc must not re-arm the machine (no infinite re-drive)");
        assertNull(evidenceItemRepository.findById(docB.getId()).orElseThrow().getExtractKey());
    }
}
