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
 * Mission B — single-pass structured extraction. Exercises the
 * {@code va-claim.extraction.single-pass=true} path of {@link ExtractionStateMachine}:
 * ONE schema'd {@code extraction_doc} job per document, parsed into a DocFacts object,
 * persisted as atoms + MedicalEvents byte-identically to the multi-pass parsers.
 *
 * <p>Substrate mirrors {@link ExtractionStateMachineTest} (DataJpaTest + the fake LLM
 * job pipeline + in-memory GCS); the only differences are the single-pass flag ON and
 * the fake provider keyed on the {@code extraction_doc} purpose.
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
        "va-claim.extraction.single-pass=true"
})
class SinglePassExtractionStateMachineTest {

    // A full DocFacts object: every section populated so persistence of each
    // legacy-shaped sub-section (atoms + MedicalEvents) can be asserted.
    static final String DOC_FACTS_FULL = """
            {
              "doc_type": "health_summary",
              "doc_date": "2023-06-01",
              "unreadable_or_unsupported": false,
              "reason": null,
              "diagnoses_not_found": false,
              "diagnoses": [
                {"diagnosis_name": "PTSD", "icd10_code": "F43.10", "date_diagnosed": "2023-02-01",
                 "diagnosing_provider": "Dr. Smith", "severity": "moderate", "status": "chronic"}
              ],
              "medications_not_found": false,
              "medications": [
                {"drug_name": "Sertraline", "dosage": "100mg", "frequency": "daily",
                 "prescriber": "Dr. Smith", "start_date": "2023-02-10", "purpose": "PTSD"}
              ],
              "service_record_not_found": false,
              "service_records": {
                "rank": "Sergeant", "branch": "Army", "enlistment_date": "2005-01-01",
                "separation_date": "2009-01-01",
                "deployments": [{"location": "Iraq", "start_date": "2007-01-01",
                                 "end_date": "2008-01-01", "combat_zone": true}],
                "awards_decorations": ["Purple Heart"]
              },
              "events_not_found": false,
              "events": [
                {"event_date": "2022-03-15", "event_type": "emergency",
                 "provider": "VA Medical Center", "summary": "ER visit for PTSD episode",
                 "medications_mentioned": ["Sertraline"], "diagnoses_mentioned": ["PTSD"]},
                {"event_date": "2023-06-01", "event_type": "primary_care",
                 "provider": "VA Primary Care", "summary": "Annual physical"}
              ],
              "atoms_not_found": false,
              "atoms": [
                {"type": "symptom", "value": "Nightmares 4x/week", "source": "intake note",
                 "confidence": 0.9, "date": "2023-06-01"},
                {"type": "functional_limitation", "value": "Cannot maintain employment",
                 "source": "statement", "confidence": 0.85, "date": null}
              ]
            }
            """;

    // Whole-document abstention: unreadable with a plain-language reason.
    static final String DOC_FACTS_UNREADABLE = """
            {
              "doc_type": "other",
              "unreadable_or_unsupported": true,
              "reason": "This file appears to be password-protected and could not be opened."
            }
            """;

    @Autowired ExtractionStateMachine extractionStateMachine;
    @Autowired LlmJobSubmitter llmJobSubmitter;
    @Autowired LlmJobPoller llmJobPoller;
    @Autowired ClaimRepository claimRepository;
    @Autowired EvidenceItemRepository evidenceItemRepository;
    @Autowired AtomRepository atomRepository;
    @Autowired MedicalEventRepository medicalEventRepository;
    @Autowired ClaimPipelineJobRepository pipelineJobRepository;
    @Autowired LlmJobRepository llmJobRepository;
    @Autowired FakeLlmAsyncProvider fakeProvider;

    private Claim claim;
    private EvidenceItem evidence1;

    @BeforeEach
    void setUp() {
        fakeProvider.reset();
        fakeProvider.setResponse("extraction_doc", DOC_FACTS_FULL);

        claim = claimRepository.save(Claim.builder()
                .userId(7L)
                .status(Claim.ClaimStatus.EXTRACTING)
                .build());

        evidence1 = evidenceItemRepository.save(EvidenceItem.builder()
                .claimId(claim.getId())
                .sourceType("pdf")
                .rawContent("VA examination report content")
                .filename("va_exam_2023.pdf")
                .processingStatus("pending")
                .build());
    }

    // -------------------------------------------------------------------------
    // Happy path: NONE → SINGLE_PASS_EXTRACTION (one job per doc)
    // -------------------------------------------------------------------------

    @Test
    void NONE_to_singlePass_oneDocJobPerEvidence() {
        // Second doc to prove one job per document.
        evidenceItemRepository.save(EvidenceItem.builder()
                .claimId(claim.getId()).sourceType("pdf")
                .rawContent("Service medical records").filename("smr_2009.pdf")
                .processingStatus("pending").build());

        extractionStateMachine.advance(claim);

        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertEquals("SINGLE_PASS_EXTRACTION", reloaded.getExtractionState(),
                "First advance() under the single-pass flag must enter SINGLE_PASS_EXTRACTION");

        List<ClaimPipelineJob> jobs = pipelineJobRepository
                .findByClaimIdAndStage(claim.getId(), "SINGLE_PASS_EXTRACTION");
        assertEquals(2, jobs.size(), "Exactly one extraction_doc job per document");

        for (ClaimPipelineJob pj : jobs) {
            assertNotNull(pj.getEvidenceId(), "Each doc job carries its evidenceId");
            LlmJob job = llmJobRepository.findById(pj.getLlmJobId()).orElseThrow();
            assertEquals("extraction_doc", job.getPurpose(),
                    "Single-pass jobs use the extraction_doc purpose for routing + cost accounting");
            assertEquals(claim.getUserId(), job.getUserId(),
                    "Each doc job carries the claim owner's userId for the per-user cap");
        }

        long docJobs = fakeProvider.submittedJobs().stream()
                .filter(j -> "extraction_doc".equals(j.purpose())).count();
        assertEquals(2, docJobs);
    }

    // -------------------------------------------------------------------------
    // Happy path: full DocFacts → atoms (all provenances) + MedicalEvents persisted,
    // evidence processed, pipeline COMPLETE.
    // -------------------------------------------------------------------------

    @Test
    void singlePass_persistsAtomsAndEvents_andCompletes() {
        // Tick to terminal then parse.
        extractionStateMachine.advance(claim);   // NONE → SINGLE_PASS_EXTRACTION
        llmJobSubmitter.tick();
        llmJobPoller.tick();
        extractionStateMachine.advance(claim);    // parse DocFacts + → COMPLETE

        // Atoms persisted from every section, each with the legacy createdBy tag.
        List<Atom> atoms = atomRepository.findByClaimId(claim.getId());
        assertFalse(atoms.isEmpty(), "Atoms must be persisted from the DocFacts sections");

        assertTrue(atoms.stream().anyMatch(a -> "ai:extraction-diagnosis".equals(a.getCreatedBy())
                        && a.getValue().contains("PTSD")),
                "Diagnosis atom persisted with ai:extraction-diagnosis provenance");
        assertTrue(atoms.stream().anyMatch(a -> "ai:extraction-medication".equals(a.getCreatedBy())
                        && a.getValue().contains("Sertraline")),
                "Medication atom persisted with ai:extraction-medication provenance");
        assertTrue(atoms.stream().anyMatch(a -> "ai:extraction-service-record".equals(a.getCreatedBy())),
                "Service-record atom persisted with ai:extraction-service-record provenance");
        assertTrue(atoms.stream().anyMatch(a -> "ai:extraction-atom".equals(a.getCreatedBy())),
                "Generic atom persisted with ai:extraction-atom provenance");

        // MedicalEvent rows persisted from the events[] section (cross-event
        // aggregation reads these — keeps that path working).
        assertEquals(2, medicalEventRepository.countByClaimId(claim.getId()),
                "Both DocFacts events must become MedicalEvent rows");

        // Evidence marked processed (not error).
        EvidenceItem ev = evidenceItemRepository.findById(evidence1.getId()).orElseThrow();
        assertEquals("processed", ev.getProcessingStatus(),
                "A readable document must end as processing_status=processed");

        // Pipeline complete.
        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertNull(reloaded.getExtractionState(),
                "extractionState must be null (COMPLETE) after single-pass finishes");
        // A completed run must NOT spontaneously restart on the next tick.
        extractionStateMachine.advance(reloaded);
        assertNull(claimRepository.findById(claim.getId()).orElseThrow().getExtractionState(),
                "Completed single-pass extraction must stay COMPLETE, not restart");
    }

    // -------------------------------------------------------------------------
    // Abstention: unreadable document → processing_status=error + plain message.
    // -------------------------------------------------------------------------

    @Test
    void singlePass_unreadable_setsErrorAndMessage() {
        fakeProvider.setResponse("extraction_doc", DOC_FACTS_UNREADABLE);

        extractionStateMachine.advance(claim);   // NONE → SINGLE_PASS_EXTRACTION
        llmJobSubmitter.tick();
        llmJobPoller.tick();
        extractionStateMachine.advance(claim);    // parse → unreadable

        EvidenceItem ev = evidenceItemRepository.findById(evidence1.getId()).orElseThrow();
        assertEquals("error", ev.getProcessingStatus(),
                "An unreadable document must be surfaced as processing_status=error, never silent-empty");
        assertNotNull(ev.getProcessingMessage(), "A plain-language processing_message must be set");
        assertTrue(ev.getProcessingMessage().toLowerCase().contains("password"),
                "The model's reason must be carried into the processing_message");

        // No atoms persisted for the unreadable doc.
        assertEquals(0, atomRepository.countByClaimId(claim.getId()),
                "Unreadable document must not persist atoms");

        // The run still terminates (COMPLETE) — an unreadable doc is not a wedge.
        assertNull(claimRepository.findById(claim.getId()).orElseThrow().getExtractionState(),
                "Pipeline must complete even when a document is unreadable");
    }

    // -------------------------------------------------------------------------
    // Parse failure: SUCCEEDED job with malformed DocFacts body → stage FAILED,
    // claim ERROR, not a silent-empty persist and not a wedge.
    // -------------------------------------------------------------------------

    @Test
    void singlePass_malformedDocFacts_failsStageNotWedge() {
        fakeProvider.setResponse("extraction_doc", "this is not valid DocFacts json at all");

        extractionStateMachine.advance(claim);   // NONE → SINGLE_PASS_EXTRACTION
        llmJobSubmitter.tick();
        llmJobPoller.tick();
        extractionStateMachine.advance(claim);    // parse throws → stage fails

        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertEquals(Claim.ClaimStatus.ERROR, reloaded.getStatus(),
                "A malformed DocFacts body must fail the claim, not silently persist nothing");
        assertNull(reloaded.getExtractionState(),
                "A failed single-pass stage must clear extractionState (no perpetual re-tick wedge)");
        assertNotNull(reloaded.getAnalysisMessage(),
                "The failure reason must be recorded on the claim");
        assertEquals(0, atomRepository.countByClaimId(claim.getId()),
                "No atoms persisted on a parse failure (no silent partial persist)");
    }

    // -------------------------------------------------------------------------
    // Partial parse failure (multi-doc): doc A valid + doc B unparseable. A's
    // atoms persist + A processed; B error; the stage COMPLETES (no wedge, no
    // claim-wide ERROR, no partial-then-abort).
    // -------------------------------------------------------------------------

    @Test
    void singlePass_oneDocValidOneUnparseable_persistsValid_marksBadError_completes() {
        // evidence1 (doc A) returns a full valid DocFacts; doc B returns garbage.
        EvidenceItem docB = evidenceItemRepository.save(EvidenceItem.builder()
                .claimId(claim.getId()).sourceType("pdf")
                .rawContent("Garbled second document").filename("bad_scan.pdf")
                .processingStatus("pending").build());

        fakeProvider.setResponseForEvidence(evidence1.getId(), DOC_FACTS_FULL);
        fakeProvider.setResponseForEvidence(docB.getId(), "not valid DocFacts json at all");

        extractionStateMachine.advance(claim);   // NONE → SINGLE_PASS_EXTRACTION
        llmJobSubmitter.tick();
        llmJobPoller.tick();
        extractionStateMachine.advance(claim);    // parse both docs

        // Doc A's atoms persisted (the valid doc keeps its output).
        List<Atom> atoms = atomRepository.findByClaimId(claim.getId());
        assertFalse(atoms.isEmpty(), "Valid doc A must keep its persisted atoms");
        assertTrue(atoms.stream().anyMatch(a -> "ai:extraction-diagnosis".equals(a.getCreatedBy())
                        && a.getValue().contains("PTSD")),
                "Doc A's diagnosis atom must survive doc B's parse failure");
        // Doc A's events persisted too.
        assertEquals(2, medicalEventRepository.countByClaimId(claim.getId()),
                "Doc A's MedicalEvents must survive doc B's parse failure");

        // Doc A processed, doc B error.
        EvidenceItem a = evidenceItemRepository.findById(evidence1.getId()).orElseThrow();
        assertEquals("processed", a.getProcessingStatus(),
                "The valid document must be marked processed");
        EvidenceItem b = evidenceItemRepository.findById(docB.getId()).orElseThrow();
        assertEquals("error", b.getProcessingStatus(),
                "The unparseable document must be marked error, not abort the run");
        assertNotNull(b.getProcessingMessage(),
                "The unparseable document must carry a plain-language processing message");

        // Stage completes; claim is NOT failed.
        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertNull(reloaded.getExtractionState(),
                "A partial doc failure must still complete the stage (no wedge)");
        assertNotEquals(Claim.ClaimStatus.ERROR, reloaded.getStatus(),
                "A partial doc failure must NOT fail the whole claim");
    }

    // -------------------------------------------------------------------------
    // LLM job FAILED (provider-side) → existing allTerminal→FAILED convention.
    // -------------------------------------------------------------------------

    @Test
    void singlePass_llmJobFailed_failsStage() {
        fakeProvider.setFailure("extraction_doc", "Vertex stream HTTP 500");

        extractionStateMachine.advance(claim);   // NONE → SINGLE_PASS_EXTRACTION
        llmJobSubmitter.tick();
        llmJobPoller.tick();
        extractionStateMachine.advance(claim);    // all terminal, not all succeeded → FAILED

        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertEquals(Claim.ClaimStatus.ERROR, reloaded.getStatus(),
                "A FAILED extraction_doc job must fail the claim via the allTerminal→FAILED convention");
        assertNull(reloaded.getExtractionState(),
                "A failed single-pass stage must not wedge the claim in SINGLE_PASS_EXTRACTION");
    }

    // -------------------------------------------------------------------------
    // Partial failure (2026-09-12 claim-24 incident): 17 documents, 3 jobs died,
    // and the stage threw away the 14 that succeeded. One document's dead job
    // must become THAT document's error — not the claim's.
    // -------------------------------------------------------------------------
    @Test
    void singlePass_oneDocJobFails_siblingsPersistAndStageCompletes() {
        EvidenceItem evidence2 = evidenceItemRepository.save(EvidenceItem.builder()
                .claimId(claim.getId()).sourceType("pdf")
                .rawContent("Service medical records").filename("smr_2009.pdf")
                .processingStatus("pending").build());
        fakeProvider.setFailureForEvidence(evidence2.getId(), "in-memory result lost (JVM restart?)");

        extractionStateMachine.advance(claim);   // NONE → SINGLE_PASS_EXTRACTION
        llmJobSubmitter.tick();
        llmJobPoller.tick();
        extractionStateMachine.advance(claim);    // one FAILED, one SUCCEEDED

        EvidenceItem good = evidenceItemRepository.findById(evidence1.getId()).orElseThrow();
        assertEquals("processed", good.getProcessingStatus(),
                "The document whose job succeeded must be persisted as processed");
        assertTrue(atomRepository.findByClaimId(claim.getId()).stream()
                        .anyMatch(a -> a.getValue().contains("PTSD")),
                "The surviving document's atoms must be persisted");

        EvidenceItem bad = evidenceItemRepository.findById(evidence2.getId()).orElseThrow();
        assertEquals("error", bad.getProcessingStatus(),
                "The document whose job failed is surfaced as a per-document error");
        assertNotNull(bad.getProcessingMessage(), "A plain-language message must explain it");

        Claim reloaded = claimRepository.findById(claim.getId()).orElseThrow();
        assertNull(reloaded.getExtractionState(), "The stage must COMPLETE, not wedge or fail");
        assertNotEquals(Claim.ClaimStatus.ERROR, reloaded.getStatus(),
                "One dead job must not fail the whole claim");

        // Error rows are excluded from the stranded-doc re-arm: no restart loop.
        extractionStateMachine.advance(reloaded);
        assertNull(claimRepository.findById(claim.getId()).orElseThrow().getExtractionState(),
                "A completed run with an errored document must stay COMPLETE");
    }
}
