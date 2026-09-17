package com.afterduty.service.extraction;

import com.afterduty.model.*;
import com.afterduty.repository.*;
import com.afterduty.service.llm.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0-3 — honest per-document status lifecycle. A document is "queued" at
 * submit (PipelineService), "processing" the moment its extraction_doc job is
 * actually submitted, and "processed" ONLY after its facts parse successfully;
 * a parse failure lands on "error" and stays there. Before this fix every doc
 * was stamped "processed" at upload — a green done badge before (or without
 * ever) being read. Substrate mirrors {@link IncrementalExtractionStateMachineTest}.
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
class ProcessingStatusLifecycleTest {

    static final String DOC_FACTS = """
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

    @Autowired ExtractionStateMachine extractionStateMachine;
    @Autowired LlmJobSubmitter llmJobSubmitter;
    @Autowired LlmJobPoller llmJobPoller;
    @Autowired ClaimRepository claimRepository;
    @Autowired EvidenceItemRepository evidenceItemRepository;
    @Autowired FakeLlmAsyncProvider fakeProvider;

    private Claim claim;
    private EvidenceItem doc;

    @BeforeEach
    void setUp() {
        fakeProvider.reset();
        Claim c = Claim.builder()
                .userId(7L).status(Claim.ClaimStatus.EXTRACTING).build();
        c.setExtractionState("NONE");
        claim = claimRepository.save(c);
        // "queued" is what PipelineService.processEvidence now leaves behind.
        doc = evidenceItemRepository.save(EvidenceItem.builder()
                .claimId(claim.getId()).sourceType("pdf")
                .rawContent("VA exam content").filename("doc.pdf")
                .fileHash("hash-1")
                .processingStatus("queued").build());
    }

    private Claim reload() {
        return claimRepository.findById(claim.getId()).orElseThrow();
    }

    private String status() {
        return evidenceItemRepository.findById(doc.getId()).orElseThrow().getProcessingStatus();
    }

    @Test
    void queued_thenProcessingAtJobSubmit_thenProcessedOnlyAfterParse() {
        fakeProvider.setResponseForEvidence(doc.getId(), DOC_FACTS);

        assertEquals("queued", status(), "Before the stage starts the doc is only queued");

        extractionStateMachine.advance(reload());   // NONE → SINGLE_PASS_EXTRACTION
        assertEquals("processing", status(),
                "A doc with a live extraction job is 'processing' — not 'processed'");

        llmJobSubmitter.tick();
        llmJobPoller.tick();
        assertEquals("processing", status(),
                "The job finishing is not enough — 'processed' requires the parse to succeed");

        extractionStateMachine.advance(reload());   // parse facts → COMPLETE
        assertEquals("processed", status(),
                "'processed' is written only after the doc's facts parsed successfully");
        assertNull(reload().getExtractionState());
    }

    @Test
    void parseFailure_landsOnError_neverProcessed() {
        fakeProvider.setResponseForEvidence(doc.getId(), "not valid DocFacts json");

        extractionStateMachine.advance(reload());
        assertEquals("processing", status());

        llmJobSubmitter.tick();
        llmJobPoller.tick();
        extractionStateMachine.advance(reload());

        assertEquals("error", status(),
                "A doc whose facts never parsed must surface 'error', not a green badge");
    }
}
