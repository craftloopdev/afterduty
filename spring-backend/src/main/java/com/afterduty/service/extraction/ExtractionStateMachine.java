package com.afterduty.service.extraction;

import com.afterduty.dto.AtomDto;
import com.afterduty.model.Atom;
import com.afterduty.model.Claim;
import com.afterduty.model.ClaimPipelineJob;
import com.afterduty.model.EvidenceItem;
import com.afterduty.model.MedicalEvent;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ClaimPipelineJobRepository;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.EvidenceItemRepository;
import com.afterduty.repository.LlmJobRepository;
import com.afterduty.repository.MedicalEventRepository;
import com.afterduty.service.DocumentStorageService;
// GeminiExtractionService is in the same package (com.afterduty.service.extraction)
import com.afterduty.service.llm.LlmJobRequest;
import com.afterduty.service.llm.LlmJobResult;
import com.afterduty.service.llm.LlmJobService;
import com.afterduty.service.rag.EvidenceEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/**
 * Hand-rolled state machine driving the extraction pipeline.
 * State stored in Claim.extractionState (string-enum). Advances at most one
 * transition per call to advance(). AnalysisScheduler.tick() drives it.
 *
 * Stages:
 *   NONE → EXTRACTING_DIAGNOSIS → EXTRACTING_MEDICATIONS →
 *   EXTRACTING_SERVICE_RECORDS → EXTRACTING_ATOMS →
 *   EXTRACTING_SEGMENTS → EXTRACTING_EVENTS → null (COMPLETE)
 */
@Service
public class ExtractionStateMachine {

    private static final Logger log = LoggerFactory.getLogger(ExtractionStateMachine.class);

    public enum State {
        NONE,
        // Single-pass structured extraction (Mission B): ONE schema'd extraction_doc
        // job per eligible document, behind va-claim.extraction.single-pass.
        SINGLE_PASS_EXTRACTION,
        EXTRACTING_DIAGNOSIS,
        EXTRACTING_MEDICATIONS,
        EXTRACTING_SERVICE_RECORDS,
        EXTRACTING_ATOMS,
        EXTRACTING_SEGMENTS,
        EXTRACTING_EVENTS,
        COMPLETE
    }

    private final ClaimRepository claimRepository;
    private final EvidenceItemRepository evidenceItemRepository;
    private final MedicalEventRepository medicalEventRepository;
    private final AtomRepository atomRepository;
    private final ClaimPipelineJobRepository pipelineJobRepository;
    private final LlmJobService llmJobService;
    private final LlmJobRepository llmJobRepository;
    private final DiagnosisExtractorService diagnosisExtractorService;
    private final MedicationExtractorService medicationExtractorService;
    private final ServiceRecordExtractorService serviceRecordExtractorService;
    private final GeminiExtractionService geminiExtractionService;
    private final EventSegmentationAgent eventSegmentationAgent;
    private final EventExtractionAgent eventExtractionAgent;
    private final DocumentStorageService documentStorageService;
    private final SinglePassExtractionService singlePassExtractionService;
    private final EvidenceEmbeddingService evidenceEmbeddingService;

    @org.springframework.beans.factory.annotation.Value("${va-claim.extraction.single-pass:true}")
    private boolean singlePassEnabled;

    /**
     * Increment 7 §B.3 — embed a document's chunks for hybrid retrieval grounding
     * right after its single-pass extraction commits. OFF → no embedding hook (the
     * backfill job still picks the doc up if rag is later enabled).
     */
    @org.springframework.beans.factory.annotation.Value("${va-claim.rag.enabled:true}")
    private boolean ragEnabled;

    /**
     * Mission 5a — incremental extraction. When true (and single-pass is on),
     * the single-pass fan-out submits an {@code extraction_doc} job ONLY for
     * documents whose stored {@code extract_key} differs from the freshly
     * computed key (never extracted, file changed, or a prompt/schema/model
     * bump); unchanged documents are skipped entirely. Re-extracted documents
     * have their prior atoms superseded before the new atoms are persisted, and
     * the {@code extract_key} is written only after that doc's parse+persist
     * succeeds. Flag OFF restores the pre-5a behavior exactly: every document is
     * re-extracted on every run, no extract_key is read or written, and atoms are
     * appended (no supersede). Rollback = set INCREMENTAL_ANALYSIS=false.
     */
    @org.springframework.beans.factory.annotation.Value("${va-claim.analysis.incremental:true}")
    private boolean incrementalEnabled;

    public ExtractionStateMachine(ClaimRepository claimRepository,
                                   EvidenceItemRepository evidenceItemRepository,
                                   MedicalEventRepository medicalEventRepository,
                                   AtomRepository atomRepository,
                                   ClaimPipelineJobRepository pipelineJobRepository,
                                   LlmJobService llmJobService,
                                   LlmJobRepository llmJobRepository,
                                   DiagnosisExtractorService diagnosisExtractorService,
                                   MedicationExtractorService medicationExtractorService,
                                   ServiceRecordExtractorService serviceRecordExtractorService,
                                   GeminiExtractionService geminiExtractionService,
                                   EventSegmentationAgent eventSegmentationAgent,
                                   EventExtractionAgent eventExtractionAgent,
                                   DocumentStorageService documentStorageService,
                                   SinglePassExtractionService singlePassExtractionService,
                                   EvidenceEmbeddingService evidenceEmbeddingService) {
        this.claimRepository = claimRepository;
        this.evidenceItemRepository = evidenceItemRepository;
        this.medicalEventRepository = medicalEventRepository;
        this.atomRepository = atomRepository;
        this.pipelineJobRepository = pipelineJobRepository;
        this.llmJobService = llmJobService;
        this.llmJobRepository = llmJobRepository;
        this.diagnosisExtractorService = diagnosisExtractorService;
        this.medicationExtractorService = medicationExtractorService;
        this.serviceRecordExtractorService = serviceRecordExtractorService;
        this.geminiExtractionService = geminiExtractionService;
        this.eventSegmentationAgent = eventSegmentationAgent;
        this.eventExtractionAgent = eventExtractionAgent;
        this.documentStorageService = documentStorageService;
        this.singlePassExtractionService = singlePassExtractionService;
        this.evidenceEmbeddingService = evidenceEmbeddingService;
    }

    @Transactional
    public void advance(Claim claim) {
        State current = parseState(claim.getExtractionState(), claim.getId());
        switch (current) {
            case NONE -> {
                // Mission B: behind va-claim.extraction.single-pass, collapse the
                // five legacy per-doc passes into one schema'd extraction_doc job
                // per document. Flag off ⇒ legacy multi-pass chain, unchanged.
                if (singlePassEnabled) {
                    startSinglePassStage(claim);
                } else {
                    startStage(claim, State.EXTRACTING_DIAGNOSIS);
                }
            }
            case SINGLE_PASS_EXTRACTION     -> advanceSinglePass(claim);
            case EXTRACTING_DIAGNOSIS       -> advanceIfReady(claim, State.EXTRACTING_DIAGNOSIS, State.EXTRACTING_MEDICATIONS);
            case EXTRACTING_MEDICATIONS     -> advanceIfReady(claim, State.EXTRACTING_MEDICATIONS, State.EXTRACTING_SERVICE_RECORDS);
            case EXTRACTING_SERVICE_RECORDS -> advanceIfReady(claim, State.EXTRACTING_SERVICE_RECORDS, State.EXTRACTING_ATOMS);
            case EXTRACTING_ATOMS           -> advanceIfReady(claim, State.EXTRACTING_ATOMS, State.EXTRACTING_SEGMENTS);
            case EXTRACTING_SEGMENTS        -> advanceIfReady(claim, State.EXTRACTING_SEGMENTS, State.EXTRACTING_EVENTS);
            case EXTRACTING_EVENTS          -> advanceIfReady(claim, State.EXTRACTING_EVENTS, State.COMPLETE);
            case COMPLETE                   -> { /* no-op */ }
        }
    }

    // -------------------------------------------------------------------------
    // startStage: fan out jobs for the next state
    // -------------------------------------------------------------------------

    private void startStage(Claim claim, State next) {
        if (next == State.EXTRACTING_EVENTS) {
            startEventExtractionStage(claim);
            return;
        }

        // For all other stages: iterate pending EvidenceItems
        List<EvidenceItem> evidenceItems = evidenceItemRepository.findByClaimId(claim.getId());
        if (evidenceItems.isEmpty()) {
            // Nothing to extract — skip to complete
            claim.setExtractionState(null);
            claimRepository.save(claim);
            log.info("[extraction] No evidence items for claim {} — marking complete", claim.getId());
            return;
        }

        String stageName = next.name();
        String batchGroupKey = purposeForStage(next) + "_" + claim.getId();

        // Clear stale rows from prior runs so parseStageResults never re-parses
        // (and re-saves atoms from) a previous run's jobs.
        pipelineJobRepository.deleteByClaimIdAndStage(claim.getId(), stageName);
        List<ClaimPipelineJob> pjobs = new ArrayList<>();

        for (EvidenceItem ev : evidenceItems) {
            LlmJobRequest req = buildRequestForStage(next, ev, claim.getId(), claim.getUserId());
            if (req == null) continue;
            UUID jobId = llmJobService.submit(req);
            pjobs.add(new ClaimPipelineJob(claim.getId(), stageName, jobId, null, ev.getId()));
            // P0-3 — a doc with a live extraction job is "processing", not
            // "queued". Don't clobber error/deferred rows the legacy multi-pass
            // re-fan-out sweeps up.
            if (isAwaitingExtraction(ev.getProcessingStatus())) {
                ev.setProcessingStatus("processing");
                evidenceItemRepository.save(ev);
            }
        }

        if (pjobs.isEmpty()) {
            // No jobs submitted (e.g. no content) — advance directly
            claim.setExtractionState(next.name());
            claimRepository.save(claim);
            return;
        }

        pipelineJobRepository.saveAll(pjobs);
        claim.setExtractionState(stageName);
        claimRepository.save(claim);
        log.info("[extraction] NONE → {} for claim {}, {} jobs", stageName, claim.getId(), pjobs.size());
    }

    private void startEventExtractionStage(Claim claim) {
        List<MedicalEvent> events = medicalEventRepository.findByClaimIdAndExtracted(claim.getId(), false);
        String stageName = State.EXTRACTING_EVENTS.name();
        String batchGroupKey = "extraction_event_" + claim.getId();

        if (events.isEmpty()) {
            // No unextracted events — advance directly to COMPLETE
            log.info("[extraction] No unextracted events for claim {} — advancing to COMPLETE", claim.getId());
            claim.setExtractionState(null);
            claimRepository.save(claim);
            return;
        }

        pipelineJobRepository.deleteByClaimIdAndStage(claim.getId(), stageName);
        List<ClaimPipelineJob> pjobs = new ArrayList<>();
        for (MedicalEvent ev : events) {
            LlmJobRequest req = eventExtractionAgent.buildRequest(ev, claim.getId());
            // Override batchGroupKey; attribute spend to the claim owner so
            // extraction AiCallLog rows count toward the per-user usage cap.
            req = LlmJobRequest.builder()
                    .purpose(req.getPurpose())
                    .systemPrompt(req.getSystemPrompt())
                    .userMessage(req.getUserMessage())
                    .maxTokens(req.getMaxTokens())
                    .thinkingBudget(req.getThinkingBudget())
                    .claimId(claim.getId())
                    .userId(claim.getUserId())
                    .evidenceId(ev.getEvidenceId())
                    .batchGroupKey(batchGroupKey)
                    .build();
            UUID jobId = llmJobService.submit(req);
            // Use evidenceId as eventId proxy (no dedicated field on ClaimPipelineJob for eventId)
            pjobs.add(new ClaimPipelineJob(claim.getId(), stageName, jobId, null, ev.getEvidenceId()));
        }

        pipelineJobRepository.saveAll(pjobs);
        claim.setExtractionState(stageName);
        claimRepository.save(claim);
        log.info("[extraction] → EXTRACTING_EVENTS for claim {}, {} events", claim.getId(), events.size());
    }

    // -------------------------------------------------------------------------
    // Single-pass structured extraction (Mission B)
    // -------------------------------------------------------------------------

    /**
     * Fan out ONE {@code extraction_doc} job per <em>eligible</em> document.
     *
     * <p>With {@code va-claim.analysis.incremental} ON (Mission 5a), eligibility
     * is the content-addressed delta: a document is submitted only when its
     * stored {@code extract_key} differs from the freshly computed key — i.e. it
     * was never extracted, its file changed, or the prompt/schema/model was
     * bumped. Documents whose key already matches are skipped entirely (their
     * prior atoms stay live and untouched); the skip count is logged. With the
     * flag OFF, eligibility is every {@link EvidenceItem} of the claim, exactly as
     * before — the full re-extract-everything semantics.
     *
     * <p>Each job carries the claim owner's userId and purpose {@code
     * extraction_doc} so AiCallLog / per-user cap accounting works.
     */
    private void startSinglePassStage(Claim claim) {
        List<EvidenceItem> evidenceItems = evidenceItemRepository.findByClaimId(claim.getId());
        if (evidenceItems.isEmpty()) {
            claim.setExtractionState(null);
            claimRepository.save(claim);
            log.info("[extraction] No evidence items for claim {} — marking complete (single-pass)",
                    claim.getId());
            return;
        }

        String stageName = State.SINGLE_PASS_EXTRACTION.name();
        // Clear stale rows from prior runs so advance never re-parses an old run's jobs.
        pipelineJobRepository.deleteByClaimIdAndStage(claim.getId(), stageName);

        // Build + submit each ELIGIBLE document INDEPENDENTLY. A single bad
        // document (missing GCS object, request-build failure, submit error) must
        // not throw out of this @Transactional method — that would roll back the
        // whole stage start, leaving extractionState=NONE so the scheduler
        // re-enters here every tick forever (a perpetual-loop wedge). Instead each
        // failure marks ONLY that evidence row processing_status=error with a
        // plain-language message and the rest proceed. We persist the pipeline-job
        // rows and the state transition AFTER the submit loop so the transition
        // never depends on every document's failure-prone work succeeding.
        List<ClaimPipelineJob> pjobs = new ArrayList<>();
        int skipped = 0;
        for (EvidenceItem ev : evidenceItems) {
            // Mission 5a delta gate: skip a document whose stored extract_key
            // already equals the freshly computed key (bytes + prompt + schema +
            // routed model all unchanged ⇒ re-extraction would be a no-op). A null
            // computed key (empty row) or null/mismatched stored key re-extracts.
            if (incrementalEnabled && isUnchanged(ev)) {
                skipped++;
                continue;
            }
            try {
                LlmJobRequest req = singlePassExtractionService.buildRequest(
                        ev, claim.getId(), claim.getUserId());
                UUID jobId = llmJobService.submit(req);
                pjobs.add(new ClaimPipelineJob(claim.getId(), stageName, jobId, null, ev.getId()));
                // P0-3 — honest status lifecycle: "processing" the moment this
                // doc's extraction job is actually submitted ("queued" before,
                // "processed" only after its facts parse successfully).
                ev.setProcessingStatus("processing");
                ev.setProcessingMessage("Analyzing \""
                        + (ev.getFilename() != null ? ev.getFilename() : "document") + "\"...");
                evidenceItemRepository.save(ev);
            } catch (Exception e) {
                log.error("[extraction] single-pass: could not submit extraction for evidence {} "
                        + "(claim {}) — {}", ev.getId(), claim.getId(), e.getMessage(), e);
                ev.setProcessingStatus("error");
                ev.setProcessingMessage("We weren't able to start analyzing this document. "
                        + "Please try uploading it again.");
                evidenceItemRepository.save(ev);
            }
        }

        if (pjobs.isEmpty()) {
            // Two very different empty-job cases:
            //  (a) Incremental run where EVERY document was unchanged (skipped>0,
            //      no submit failures): this is the headline win — nothing to
            //      re-extract. Complete the stage cleanly; existing atoms remain.
            //  (b) Nothing could be submitted because every eligible doc failed to
            //      build/submit (skipped==0 here for the changed set): fail the
            //      stage so the veteran is told, rather than silently "completing"
            //      with no analysis.
            if (incrementalEnabled && skipped > 0) {
                claim.setExtractionState(null);
                claimRepository.save(claim);
                log.info("[extraction] single-pass: all {} document(s) unchanged for claim {} — "
                        + "nothing to re-extract, marking complete", skipped, claim.getId());
                return;
            }
            // Not one document could be submitted. Fail the stage cleanly rather
            // than leaving it un-started (which would re-tick forever). The per-doc
            // error messages above already explain the failure to the veteran.
            failExtractionWithMessage(claim, stageName,
                    "We couldn't start analyzing any of the uploaded documents.");
            return;
        }

        pipelineJobRepository.saveAll(pjobs);
        claim.setExtractionState(stageName);
        claimRepository.save(claim);
        log.info("[extraction] NONE → SINGLE_PASS_EXTRACTION for claim {}, {} doc job(s), {} unchanged "
                + "doc(s) skipped", claim.getId(), pjobs.size(), skipped);
    }

    /**
     * True when {@code ev} is unchanged since its last successful extraction:
     * it has a non-null stored {@code extract_key} that equals the freshly
     * computed key. A null stored key (never extracted, or a prior failure that
     * left it null) and a null computed key (empty row) both return false so the
     * document is (re-)extracted.
     */
    private boolean isUnchanged(EvidenceItem ev) {
        String stored = ev.getExtractKey();
        if (stored == null || stored.isBlank()) {
            return false;
        }
        String current = singlePassExtractionService.computeExtractKey(ev);
        return current != null && current.equals(stored);
    }

    /**
     * Drive the single-pass stage: wait for all extraction_doc jobs to terminate,
     * fail the stage (existing allTerminal→FAILED convention) if any LLM job FAILED,
     * else parse each DocFacts result and persist atoms / MedicalEvents. Unreadable
     * documents become {@code processing_status=error} with a plain-language
     * {@code processing_message}; readable docs become {@code processed}. A DocFacts
     * that cannot be parsed (job SUCCEEDED but body malformed) fails the stage rather
     * than silently persisting nothing.
     */
    private void advanceSinglePass(Claim claim) {
        String stageName = State.SINGLE_PASS_EXTRACTION.name();
        List<ClaimPipelineJob> pjobs = pipelineJobRepository.findByClaimIdAndStage(claim.getId(), stageName);
        if (pjobs.isEmpty()) {
            transitionTo(claim, State.COMPLETE);
            return;
        }

        Set<UUID> jobIds = collectJobIds(pjobs);
        if (!llmJobService.allTerminal(jobIds)) {
            log.debug("[extraction] single-pass stage not yet complete for claim {}", claim.getId());
            return;
        }
        if (!llmJobService.allSucceeded(jobIds)) {
            // 2026-09-12: three of seventeen document jobs died in an OutOfMemoryError
            // and the whole stage failed, discarding the fourteen that succeeded. A
            // dead job is now THAT document's error; only an all-failed stage is the
            // claim's. Error rows are excluded from the stranded-doc re-arm, so this
            // cannot loop — the veteran re-uploads the document to retry it.
            Set<UUID> failedIds = failedJobIds(jobIds);
            if (failedIds.size() >= jobIds.size()) {
                failExtraction(claim, stageName, jobIds);
                return;
            }
            List<ClaimPipelineJob> survivors = new java.util.ArrayList<>(pjobs.size());
            for (ClaimPipelineJob pj : pjobs) {
                if (failedIds.contains(pj.getLlmJobId())) {
                    markDocumentFailed(claim, pj);
                } else {
                    survivors.add(pj);
                }
            }
            log.warn("[extraction] single-pass: {} of {} document job(s) failed for claim {} — "
                    + "keeping the {} that succeeded", failedIds.size(), jobIds.size(),
                    claim.getId(), survivors.size());
            pjobs = survivors;
        }

        SinglePassParseTally tally = parseSinglePassResults(claim, pjobs);

        // Only fail the whole stage when EVERY document with a result failed to
        // parse — a partial set of bad docs must not abort the docs that parsed
        // fine (those keep their atoms and are marked processed). One doc's
        // unparseable DocFacts becomes a per-doc processing_status=error, not a
        // claim-wide ERROR with partial persisted data.
        if (tally.attempted > 0 && tally.succeeded == 0) {
            failExtractionWithMessage(claim, stageName,
                    "We couldn't read the analysis results for any of the uploaded documents.");
            return;
        }

        transitionTo(claim, State.COMPLETE);
    }

    /** Per-document outcome counts from a single-pass parse pass. */
    private record SinglePassParseTally(int attempted, int succeeded, int failed) {}

    private SinglePassParseTally parseSinglePassResults(Claim claim, List<ClaimPipelineJob> pjobs) {
        int attempted = 0;
        int succeeded = 0;
        int failed = 0;

        for (ClaimPipelineJob pj : pjobs) {
            LlmJobResult result = llmJobService.getResult(pj.getLlmJobId()).orElse(null);
            if (result == null) continue;

            Long evidenceId = pj.getEvidenceId();
            EvidenceItem evidence = evidenceId != null
                    ? evidenceItemRepository.findById(evidenceId).orElse(null)
                    : null;
            if (evidence == null) continue;

            attempted++;

            // PER-DOCUMENT isolation: a doc whose DocFacts body is unparseable
            // (the job SUCCEEDED but the JSON is malformed) gets marked error +
            // a plain message, and the loop CONTINUES so docs that parsed fine
            // keep their atoms. doc #1 committing then a later doc throwing must
            // never abort the run with partial data. Only when EVERY attempted
            // doc fails (succeeded==0, checked by the caller) does the stage fail.
            SinglePassExtractionService.DocFactsOutcome outcome;
            try {
                outcome = singlePassExtractionService.parseDocFacts(result, evidence);
            } catch (SinglePassExtractionService.DocFactsParseException e) {
                failed++;
                // e.getMessage() is already PHI-safe (class + line/col only).
                log.error("[extraction] single-pass: DocFacts parse failed for evidence {} "
                        + "(claim {}) — {}", evidence.getId(), claim.getId(), e.getMessage());
                evidence.setProcessingStatus("error");
                evidence.setProcessingMessage("We couldn't read the analysis results for this "
                        + "document. Please try uploading it again.");
                evidenceItemRepository.save(evidence);
                continue;
            }

            if (outcome.unreadable) {
                // Honest failure — never a silent-empty extraction. An unreadable
                // doc is a per-doc error, not a stage failure: it counts as a
                // resolved (non-throwing) outcome so it does not wedge the run.
                // We do NOT supersede prior atoms and do NOT write an extract_key:
                // an unreadable result must leave the document re-extractable (a
                // later clearer re-upload changes the file_hash → key mismatch →
                // re-extract) and must not silently wipe atoms from a prior good
                // run. The new (empty) generation simply isn't persisted.
                succeeded++;
                evidence.setProcessingStatus("error");
                evidence.setProcessingMessage(outcome.reason);
                evidenceItemRepository.save(evidence);
                log.info("[extraction] single-pass: evidence {} unreadable — {}",
                        evidence.getId(), outcome.reason);
                continue;
            }

            // Mission 5a — supersede-on-reextract. When incremental is on, retire
            // this document's prior LIVE atoms BEFORE persisting the new ones,
            // atomically in this same @Transactional method, so no reader ever
            // sees both generations of the same evidence at once. The supersede
            // marker is the document's own evidenceId: non-null (so the filter
            // `superseded_by IS NULL` excludes them) and carrying the provenance
            // of which re-extracted document retired them. A first extraction
            // supersedes zero rows (the no-op is cheap and harmless). Flag OFF
            // keeps the legacy append-only behavior — prior atoms are left live,
            // which is correct because flag-off also never skips re-extraction, so
            // every reader is rebuilt from a full pass anyway.
            if (incrementalEnabled && evidenceId != null) {
                int retired = atomRepository.supersedeLiveAtomsForEvidence(evidenceId, evidenceId);
                if (retired > 0) {
                    log.info("[extraction] single-pass: superseded {} prior atom(s) for re-extracted "
                            + "evidence {} (claim {})", retired, evidenceId, claim.getId());
                }
            }

            // Persist each section's atoms with the legacy createdBy provenance.
            for (Map.Entry<String, List<AtomDto>> group : outcome.atomsByProvenance.entrySet()) {
                saveAtoms(group.getValue(), claim.getId(), evidenceId, group.getKey());
            }

            succeeded++;
            evidence.setProcessingStatus("processed");
            // Record the multimodal→text fallback note (if any) as a friendly message,
            // without overriding the processed state. Null clears any prior message.
            evidence.setProcessingMessage(singlePassExtractionService.inlineFallbackMessage(evidence));
            // Mission 5a — write the extract_key ONLY after this doc's parse+persist
            // succeeded. A doc that failed to parse, was unreadable, or failed to
            // submit never reaches here, so it keeps a null/stale key and is
            // re-extracted next round (failure isolation from Increment 4 intact).
            // Flag OFF never writes the key (delta gate is also off), so the column
            // stays null and behavior is unchanged.
            if (incrementalEnabled) {
                evidence.setExtractKey(singlePassExtractionService.computeExtractKey(evidence));
            }
            evidenceItemRepository.save(evidence);

            // Increment 7 §B.3 — embed this document's chunks for hybrid retrieval
            // grounding. The Vertex HTTP call must NOT run inside this @Transactional
            // method, so register an afterCommit callback that fires it on a virtual
            // thread once the extraction transaction has durably committed. Guarded by
            // va-claim.rag.enabled; the backfill job self-heals any missed hook.
            if (ragEnabled && evidenceId != null) {
                final Long embedEvidenceId = evidenceId;
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(
                            new TransactionSynchronization() {
                                @Override
                                public void afterCommit() {
                                    Thread.ofVirtual().name("evidence-embed-" + embedEvidenceId)
                                            .start(() -> safeEmbed(embedEvidenceId));
                                }
                            });
                } else {
                    Thread.ofVirtual().name("evidence-embed-" + embedEvidenceId)
                            .start(() -> safeEmbed(embedEvidenceId));
                }
            }
        }

        return new SinglePassParseTally(attempted, succeeded, failed);
    }

    /**
     * Increment 7 §B.3 — embed one document's chunks off the extraction transaction,
     * swallowing failures (the backfill job retries pending chunks).
     */
    private void safeEmbed(Long evidenceId) {
        try {
            evidenceEmbeddingService.embedEvidence(evidenceId);
        } catch (Exception e) {
            log.warn("[extraction] post-commit embedding for evidence {} failed: {}",
                    evidenceId, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // advanceIfReady: check all jobs in current stage, parse results, move to next
    // -------------------------------------------------------------------------

    private void advanceIfReady(Claim claim, State current, State next) {
        String stageName = current.name();
        List<ClaimPipelineJob> pjobs = pipelineJobRepository.findByClaimIdAndStage(claim.getId(), stageName);

        // If no pipeline jobs exist for this stage, it was a zero-job stage — advance immediately
        if (pjobs.isEmpty()) {
            transitionTo(claim, next);
            return;
        }

        Set<UUID> jobIds = collectJobIds(pjobs);
        if (!llmJobService.allTerminal(jobIds)) {
            log.debug("[extraction] {} stage not yet complete for claim {}", stageName, claim.getId());
            return;
        }
        if (!llmJobService.allSucceeded(jobIds)) {
            failExtraction(claim, stageName, jobIds);
            return;
        }

        // Parse results — side effects persist atoms/events
        parseStageResults(claim, current, pjobs);

        // Transition to next state
        transitionTo(claim, next);
    }

    private void transitionTo(Claim claim, State next) {
        if (next == State.COMPLETE) {
            // P0-6 — stranded-doc re-arm. A document uploaded (or changed) while a
            // stage was already in flight joins no stage; nulling extractionState
            // here would leave it silently unextracted until some future upload
            // re-armed the machine — and synthesis would run without its facts.
            // If any doc still lacks a current extract_key, go back to NONE so the
            // scheduler re-drives extraction for exactly the stale/missed docs
            // (the incremental delta gate skips the unchanged ones). Only the
            // single-pass+incremental path writes extract_keys, so only it can
            // tell a stranded doc apart — the legacy/flag-off paths keep the old
            // terminal behavior (re-arming there would loop forever).
            if (singlePassEnabled && incrementalEnabled) {
                long stranded = evidenceItemRepository.findByClaimId(claim.getId()).stream()
                        .filter(this::isStranded)
                        .count();
                if (stranded > 0) {
                    claim.setExtractionState(State.NONE.name());
                    claimRepository.save(claim);
                    log.info("[extraction] claim {} has {} unextracted document(s) at stage end — "
                            + "re-arming to NONE instead of completing", claim.getId(), stranded);
                    return;
                }
            } else {
                // P0-3, legacy multi-pass only: there is no per-doc parse-success
                // signal (facts were parsed stage-by-stage and a failed stage never
                // reaches here), so surviving in-flight docs are processed now.
                for (EvidenceItem ev : evidenceItemRepository.findByClaimId(claim.getId())) {
                    if (isAwaitingExtraction(ev.getProcessingStatus())
                            || "processing".equals(ev.getProcessingStatus())) {
                        ev.setProcessingStatus("processed");
                        ev.setProcessingMessage(null);
                        evidenceItemRepository.save(ev);
                    }
                }
            }
            claim.setExtractionState(null);
            claimRepository.save(claim);
            log.info("[extraction] → COMPLETE for claim {}", claim.getId());
        } else {
            startStage(claim, next);
        }
    }

    /** P0-3 — statuses that mean "no extraction job has touched this doc yet". */
    private boolean isAwaitingExtraction(String status) {
        return status == null || "pending".equals(status) || "queued".equals(status);
    }

    /**
     * P0-6 — true when this document should have been extracted this run but was
     * not: its stored extract_key is null (never extracted) or stale (file/prompt/
     * schema/model changed) AND it is not in a terminal per-doc failure state
     * (error rows stay re-extractable via the next upload/reprocess; deferred rows
     * resume via the usage reset). A null COMPUTED key means the row has no
     * extractable content at all — never re-arm on it, or an empty row would
     * re-drive the machine forever.
     */
    private boolean isStranded(EvidenceItem ev) {
        String status = ev.getProcessingStatus();
        if ("error".equals(status) || "deferred_usage_limit".equals(status)) {
            return false;
        }
        String computed = singlePassExtractionService.computeExtractKey(ev);
        if (computed == null) {
            return false;
        }
        return !computed.equals(ev.getExtractKey());
    }

    // -------------------------------------------------------------------------
    // Parse stage results (side-effecting: writes atoms / events)
    // -------------------------------------------------------------------------

    private void parseStageResults(Claim claim, State current, List<ClaimPipelineJob> pjobs) {
        for (ClaimPipelineJob pj : pjobs) {
            LlmJobResult result;
            try {
                result = llmJobService.getResult(pj.getLlmJobId()).orElse(null);
                if (result == null) continue;
            } catch (Exception e) {
                log.warn("[extraction] Could not get result for job {}: {}", pj.getLlmJobId(), e.getMessage());
                continue;
            }

            Long evidenceId = pj.getEvidenceId();
            EvidenceItem evidence = evidenceId != null
                    ? evidenceItemRepository.findById(evidenceId).orElse(null)
                    : null;
            String filename = evidence != null && evidence.getFilename() != null
                    ? evidence.getFilename()
                    : "unknown";

            switch (current) {
                case EXTRACTING_DIAGNOSIS -> {
                    List<AtomDto> atoms = diagnosisExtractorService.parseResponse(result, filename);
                    saveAtoms(atoms, claim.getId(), evidenceId, "ai:extraction-diagnosis");
                }
                case EXTRACTING_MEDICATIONS -> {
                    List<AtomDto> atoms = medicationExtractorService.parseResponse(result, filename);
                    saveAtoms(atoms, claim.getId(), evidenceId, "ai:extraction-medication");
                }
                case EXTRACTING_SERVICE_RECORDS -> {
                    List<AtomDto> atoms = serviceRecordExtractorService.parseResponse(result, filename);
                    saveAtoms(atoms, claim.getId(), evidenceId, "ai:extraction-service-record");
                }
                case EXTRACTING_ATOMS -> {
                    List<AtomDto> atoms = geminiExtractionService.parseResponse(result, filename);
                    saveAtoms(atoms, claim.getId(), evidenceId, "ai:extraction-atom");
                }
                case EXTRACTING_SEGMENTS -> {
                    if (evidence != null) {
                        eventSegmentationAgent.parseResponse(result, evidence);
                    }
                }
                case EXTRACTING_EVENTS -> {
                    // Find the MedicalEvent(s) for this job; use evidenceId as proxy
                    if (evidenceId != null) {
                        List<MedicalEvent> unextracted = medicalEventRepository
                                .findByClaimIdAndExtracted(claim.getId(), false);
                        for (MedicalEvent ev : unextracted) {
                            if (evidenceId.equals(ev.getEvidenceId())) {
                                List<AtomDto> atoms = eventExtractionAgent.parseResponse(result, ev);
                                saveAtoms(atoms, claim.getId(), evidenceId, "ai:extraction-event");
                                break; // Only match one event per pipeline job
                            }
                        }
                    }
                }
                default -> { /* no-op */ }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Build LlmJobRequest per stage
    // -------------------------------------------------------------------------

    private LlmJobRequest buildRequestForStage(State stage, EvidenceItem ev, Long claimId, Long userId) {
        // Storage truth: GCS-backed file rows reconstruct the canonical base64
        // envelope from GCS; legacy rows + typed text fall back to raw_content.
        // Byte-identical to the pre-storage-truth string so extraction is unchanged.
        String text = documentStorageService.loadExtractionText(ev);
        String filename = ev.getFilename() != null ? ev.getFilename() : "unknown";
        Long evidenceId = ev.getId();
        String batchGroupKey = purposeForStage(stage) + "_" + claimId;

        LlmJobRequest req = switch (stage) {
            case EXTRACTING_DIAGNOSIS ->
                    diagnosisExtractorService.buildRequest(text, filename, claimId, evidenceId);
            case EXTRACTING_MEDICATIONS ->
                    medicationExtractorService.buildRequest(text, filename, claimId, evidenceId);
            case EXTRACTING_SERVICE_RECORDS ->
                    serviceRecordExtractorService.buildRequest(text, filename, claimId, evidenceId);
            case EXTRACTING_ATOMS ->
                    geminiExtractionService.buildRequest(text, filename, claimId, evidenceId);
            case EXTRACTING_SEGMENTS ->
                    eventSegmentationAgent.buildRequest(text, filename, claimId, evidenceId);
            default -> null;
        };

        if (req == null) return null;

        // Override batchGroupKey to ensure it's the claim-level key. Attribute
        // spend to the claim owner — userId(null) here made extraction AiCallLog
        // rows invisible to the per-user usage cap.
        return LlmJobRequest.builder()
                .purpose(req.getPurpose())
                .systemPrompt(req.getSystemPrompt())
                .userMessage(req.getUserMessage())
                .maxTokens(req.getMaxTokens())
                .thinkingBudget(req.getThinkingBudget())
                .claimId(claimId)
                .userId(userId)
                .evidenceId(evidenceId)
                .batchGroupKey(batchGroupKey)
                .build();
    }

    private String purposeForStage(State stage) {
        return switch (stage) {
            case EXTRACTING_DIAGNOSIS       -> "extraction_diagnosis";
            case EXTRACTING_MEDICATIONS     -> "extraction_medication";
            case EXTRACTING_SERVICE_RECORDS -> "extraction_service_record";
            case EXTRACTING_ATOMS           -> "extraction_atom";
            case EXTRACTING_SEGMENTS        -> "extraction_event_segment";
            case EXTRACTING_EVENTS          -> "extraction_event";
            default                         -> "extraction_unknown";
        };
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private void saveAtoms(List<AtomDto> dtos, Long claimId, Long evidenceId, String createdBy) {
        for (AtomDto dto : dtos) {
            if (dto.getValue() == null || dto.getValue().isBlank()) continue;
            Atom atom = Atom.builder()
                    .claimId(claimId)
                    .evidenceId(evidenceId)
                    .type(dto.getType() != null ? dto.getType() : "unknown")
                    .value(dto.getValue())
                    .source(dto.getSource() != null ? dto.getSource()
                            : "evidence:" + evidenceId)
                    .confidence(dto.getConfidence() != null ? dto.getConfidence() : 0.8)
                    .timestamp(dto.getDate())
                    .createdBy(createdBy)
                    .build();
            atomRepository.save(atom);
        }
    }

    private State parseState(String s, Long claimId) {
        if (s == null) {
            // Null can mean either "never started" (NONE) or "completed" (COMPLETE).
            // Distinguish by checking whether any extraction pipeline jobs exist for this
            // claim. If they do, extraction has previously run and null means COMPLETE.
            // This prevents advance() from restarting a completed extraction pipeline.
            if (claimId != null) {
                List<String> extractionStages = List.of(
                        State.SINGLE_PASS_EXTRACTION.name(),
                        State.EXTRACTING_DIAGNOSIS.name(),
                        State.EXTRACTING_MEDICATIONS.name(),
                        State.EXTRACTING_SERVICE_RECORDS.name(),
                        State.EXTRACTING_ATOMS.name(),
                        State.EXTRACTING_SEGMENTS.name(),
                        State.EXTRACTING_EVENTS.name()
                );
                long existingJobs = pipelineJobRepository.countByClaimIdAndStageIn(claimId, extractionStages);
                if (existingJobs > 0) {
                    return State.COMPLETE;
                }
            }
            return State.NONE;
        }
        try {
            return State.valueOf(s);
        } catch (IllegalArgumentException e) {
            log.warn("[extraction] Unknown extractionState '{}' — treating as NONE", s);
            return State.NONE;
        }
    }

    private Set<UUID> collectJobIds(List<ClaimPipelineJob> pjobs) {
        Set<UUID> ids = new HashSet<>();
        for (ClaimPipelineJob pj : pjobs) ids.add(pj.getLlmJobId());
        return ids;
    }

    /**
     * Terminal failure: one or more jobs in the stage FAILED. Previously a
     * FAILED job left allSucceeded false forever, so the scheduler ticked the
     * dead stage every 15s and the veteran saw a permanently in-progress
     * analysis. Clearing extractionState ends the run (parseState(null) reads
     * COMPLETE because pipeline-job rows exist) and the error is surfaced via
     * the claim's existing failure representation (status=ERROR +
     * analysisMessage, same shape PipelineService uses). The next upload
     * resets extractionState to NONE and restarts extraction naturally.
     */
    /** The subset of {@code jobIds} whose LlmJob row is FAILED. */
    private Set<UUID> failedJobIds(Set<UUID> jobIds) {
        Set<UUID> failed = new java.util.HashSet<>();
        for (com.afterduty.model.LlmJob j : llmJobRepository.findAllById(jobIds)) {
            if (j.getStatus() == com.afterduty.model.LlmJob.Status.FAILED) failed.add(j.getId());
        }
        return failed;
    }

    /**
     * One document's job failed while its siblings succeeded: surface it on the
     * document (processing_status=error + a plain message) so the stage can still
     * complete. The row is re-extractable via a fresh upload, never via re-arm.
     */
    private void markDocumentFailed(Claim claim, ClaimPipelineJob pj) {
        String detail = llmJobRepository.findById(pj.getLlmJobId())
                .map(com.afterduty.model.LlmJob::getErrorMessage)
                .orElse("LLM job failed");
        log.error("[extraction] single-pass: document job failed for evidence {} (claim {}) — {}",
                pj.getEvidenceId(), claim.getId(), detail);
        if (pj.getEvidenceId() == null) return;
        evidenceItemRepository.findById(pj.getEvidenceId()).ifPresent(evidence -> {
            evidence.setProcessingStatus("error");
            evidence.setProcessingMessage("We couldn't process this document. "
                    + "Please try uploading it again.");
            evidenceItemRepository.save(evidence);
        });
    }

    private void failExtraction(Claim claim, String stageName, Set<UUID> jobIds) {
        String detail = llmJobRepository.findAllById(jobIds).stream()
                .filter(j -> j.getStatus() == com.afterduty.model.LlmJob.Status.FAILED)
                .map(j -> j.getErrorMessage())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("LLM job failed");
        String error = stageName + "_failed: " + detail;
        log.error("[extraction] Stage {} FAILED for claim {} — {}", stageName, claim.getId(), error);
        claim.setExtractionState(null);
        claim.setStatus(Claim.ClaimStatus.ERROR);
        claim.setAnalysisMessage(error);
        // Clear the upload-time stage/pct so clients render the ERROR, not a
        // frozen "extracting — 5%" (P0-4, same clearing the scheduler terminals do).
        claim.setAnalysisStage(null);
        claim.setAnalysisProgressPct(null);
        claimRepository.save(claim);
    }

    /**
     * Terminal failure with an explicit, already-safe message (no LLM job to read
     * an error from — e.g. nothing could be submitted, or every document's body was
     * unparseable). Same end-state as {@link #failExtraction}: clear extractionState
     * (so parseState reads COMPLETE and the stage stops re-ticking) and surface the
     * failure via status=ERROR + analysisMessage.
     */
    private void failExtractionWithMessage(Claim claim, String stageName, String message) {
        String error = stageName + "_failed: " + message;
        log.error("[extraction] Stage {} FAILED for claim {} — {}", stageName, claim.getId(), error);
        claim.setExtractionState(null);
        claim.setStatus(Claim.ClaimStatus.ERROR);
        claim.setAnalysisMessage(error);
        claim.setAnalysisStage(null);
        claim.setAnalysisProgressPct(null);
        claimRepository.save(claim);
    }
}
