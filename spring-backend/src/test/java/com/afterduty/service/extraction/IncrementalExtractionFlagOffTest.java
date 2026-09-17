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
 * Mission 5a — flag OFF ({@code va-claim.analysis.incremental=false}) must
 * preserve today's exact semantics on the single-pass path: every document is
 * re-extracted on every run regardless of its (never-written) extract_key, and
 * atoms are appended without supersede. This is the rollback safety net.
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
        "va-claim.analysis.incremental=false"
})
class IncrementalExtractionFlagOffTest {

    static final String DOC_FACTS_A = """
            {
              "doc_type": "health_summary",
              "unreadable_or_unsupported": false,
              "diagnoses_not_found": false,
              "diagnoses": [{"diagnosis_name": "PTSD", "icd10_code": "F43.10", "status": "chronic"}],
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
        claim = claimRepository.save(Claim.builder()
                .userId(7L).status(Claim.ClaimStatus.EXTRACTING).build());
        docA = evidenceItemRepository.save(EvidenceItem.builder()
                .claimId(claim.getId()).sourceType("pdf")
                .rawContent("VA exam content A").filename("docA.pdf")
                .fileHash("hash-A-v1").processingStatus("pending").build());
    }

    private Claim reload() {
        return claimRepository.findById(claim.getId()).orElseThrow();
    }

    private void runExtractionToCompletion() {
        extractionStateMachine.advance(reload());
        llmJobSubmitter.tick();
        llmJobPoller.tick();
        extractionStateMachine.advance(reload());
    }

    @Test
    void flagOff_neverWritesExtractKey_andReExtractsEverything() {
        // Round 1.
        fakeProvider.setResponseForEvidence(docA.getId(), DOC_FACTS_A);
        runExtractionToCompletion();

        EvidenceItem a = evidenceItemRepository.findById(docA.getId()).orElseThrow();
        assertEquals("processed", a.getProcessingStatus());
        assertNull(a.getExtractKey(),
                "Flag OFF must never write extract_key (preserves pre-5a behavior)");
        long liveAfterR1 = atomRepository.countByClaimId(claim.getId());
        assertTrue(liveAfterR1 > 0);

        // Round 2: re-trigger with the SAME unchanged doc. Flag OFF ⇒ it is
        // re-extracted anyway (no delta gate), and atoms are appended (no supersede).
        fakeProvider.reset();
        fakeProvider.setResponseForEvidence(docA.getId(), DOC_FACTS_A);
        Claim c = reload();
        c.setExtractionState("NONE");
        claimRepository.save(c);

        extractionStateMachine.advance(reload());
        List<ClaimPipelineJob> r2 = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "SINGLE_PASS_EXTRACTION");
        assertEquals(1, r2.size(),
                "Flag OFF re-extracts the unchanged doc (no skip) — a job is submitted");
        assertEquals(docA.getId(), r2.get(0).getEvidenceId());

        llmJobSubmitter.tick();
        llmJobPoller.tick();
        extractionStateMachine.advance(reload());

        // No supersede happened: all atoms for the doc remain live (superseded_by null).
        assertEquals(atomRepository.countByClaimId(claim.getId()),
                atomRepository.countByClaimIdAndSupersededByIsNull(claim.getId()),
                "Flag OFF must not supersede any atoms");
        assertTrue(atomRepository.countByClaimId(claim.getId()) > liveAfterR1,
                "Flag OFF appends on re-extraction (pre-5a append-only behavior preserved)");
        // Still no key written on the second success either.
        assertNull(evidenceItemRepository.findById(docA.getId()).orElseThrow().getExtractKey(),
                "Flag OFF never writes extract_key on any run");
    }
}
