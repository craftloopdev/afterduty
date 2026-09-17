package com.afterduty.controller;

import com.afterduty.config.SecurityConfig;
import com.afterduty.dto.*;
import com.afterduty.model.*;
import com.afterduty.repository.*;
import com.afterduty.model.PipelineMetrics;
import com.afterduty.repository.PipelineMetricsRepository;
import com.afterduty.service.AccessScope;
import com.afterduty.service.ClaimAccess;
import com.afterduty.service.ClaimAccessService;
import com.afterduty.service.DocumentStorageService;
import com.afterduty.service.PipelineService;
import com.afterduty.service.gap.UserGapStateService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/claim")
public class IntakeController {

    private static final Logger log = LoggerFactory.getLogger(IntakeController.class);

    /** Sanity ceiling for quick-add text statements (short statements, not documents). */
    private static final int QUICK_ADD_MAX_CHARS = 10_000;

    private final ClaimRepository claimRepository;
    private final EvidenceRepository evidenceRepository;
    private final ConditionRepository conditionRepository;
    private final AtomRepository atomRepository;
    private final MessageRepository messageRepository;
    private final PipelineService pipelineService;
    private final DocumentStorageService documentStorageService;
    private final com.afterduty.service.EvidenceDeletionService evidenceDeletionService;
    private final PipelineMetricsRepository pipelineMetricsRepository;
    private final com.afterduty.service.ConditionPostProcessService conditionPostProcessService;
    private final com.afterduty.service.ChatAgent chatAgent;
    private final ClaimAccessService claimAccessService;
    private final com.afterduty.service.ChatService chatService;
    private final com.afterduty.security.AdminCheck adminCheck;
    private final UserGapStateService userGapStateService;
    private final UserRepository userRepository;
    private final com.afterduty.config.SubscriptionProperties subscriptionProperties;
    private final com.afterduty.service.SubscriptionAccess subscriptionAccess;

    public IntakeController(ClaimRepository claimRepository, EvidenceRepository evidenceRepository,
                            ConditionRepository conditionRepository, AtomRepository atomRepository,
                            MessageRepository messageRepository, PipelineService pipelineService,
                            DocumentStorageService documentStorageService,
                            PipelineMetricsRepository pipelineMetricsRepository,
                            com.afterduty.service.ConditionPostProcessService conditionPostProcessService,
                            com.afterduty.service.ChatAgent chatAgent,
                            ClaimAccessService claimAccessService,
                            com.afterduty.service.ChatService chatService,
                            com.afterduty.security.AdminCheck adminCheck,
                            UserGapStateService userGapStateService,
                            UserRepository userRepository,
                            com.afterduty.config.SubscriptionProperties subscriptionProperties,
                            com.afterduty.service.SubscriptionAccess subscriptionAccess,
                            com.afterduty.service.EvidenceDeletionService evidenceDeletionService) {
        this.evidenceDeletionService = evidenceDeletionService;
        this.claimRepository = claimRepository;
        this.evidenceRepository = evidenceRepository;
        this.conditionRepository = conditionRepository;
        this.atomRepository = atomRepository;
        this.messageRepository = messageRepository;
        this.pipelineService = pipelineService;
        this.documentStorageService = documentStorageService;
        this.pipelineMetricsRepository = pipelineMetricsRepository;
        this.conditionPostProcessService = conditionPostProcessService;
        this.chatAgent = chatAgent;
        this.claimAccessService = claimAccessService;
        this.chatService = chatService;
        this.adminCheck = adminCheck;
        this.userGapStateService = userGapStateService;
        this.userRepository = userRepository;
        this.subscriptionProperties = subscriptionProperties;
        this.subscriptionAccess = subscriptionAccess;
    }

    // --- Claim ---

    @GetMapping
    public ClaimResponse getActiveClaim(HttpServletRequest request) {
        User user = getUser(request);
        ClaimAndAccess ca = resolveClaimAndAccess(user, request, AccessScope.VIEW_DOCS);
        return toClaimResponse(ca.claim);
    }

    // --- Evidence Upload (multipart) ---

    @PostMapping(value = "/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public EvidenceResponse uploadEvidence(@RequestParam("file") MultipartFile file,
                                            HttpServletRequest request) {
        User user = getUser(request);
        // Evidence upload is FREE (free tier includes uploads). The paid AI
        // processing (extraction/synthesis/gap analysis) stays gated separately
        // in AnalysisScheduler + the /analyze and /quick-add endpoints.
        ClaimAndAccess ca = resolveClaimAndAccess(user, request, AccessScope.UPLOAD_DOCS);
        Claim claim = ca.claim;

        // Size validation (50 MB)
        if (file.getSize() > 50 * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "File is too large. Maximum upload size is 50 MB.");
        }

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read uploaded file");
        }

        // Compute hash and check for duplicates
        String fileHash = documentStorageService.computeHash(fileBytes);
        if (documentStorageService.isDuplicate(claim.getId(), fileHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This file has already been uploaded to this claim.");
        }

        String filename = file.getOriginalFilename();
        // Client-declared MIME is attacker-controlled (stored XSS via the
        // download Content-Type if trusted) — allowlist it like the JSON path.
        String mediaType = documentStorageService.normalizeMediaType(file.getContentType());

        // Storage truth: the file bytes live in GCS, not base64-in-Postgres.
        // Upload to a content-addressed object path (claims/{claimId}/evidence/{fileHash})
        // so identical bytes dedupe naturally, with the original media type on the
        // object metadata. Persisting the row only after a successful upload — if
        // GCS write fails we fail the request loudly so we never leave a row that
        // points at a missing object. raw_content is left null for file uploads;
        // readers reconstruct the extraction text / file bytes from GCS via
        // DocumentStorageService. (Legacy rows with base64-in-raw_content and no
        // gcs_path keep working through the same accessors.)
        String objectPath = documentStorageService.buildEvidenceObjectPath(claim.getId(), fileHash);
        try {
            documentStorageService.uploadToGcs(fileBytes, objectPath, mediaType);
        } catch (Exception e) {
            log.error("GCS upload failed for claim {} object {}: {}", claim.getId(), objectPath, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to store the uploaded file. Please try again.");
        }

        EvidenceItem evidence = EvidenceItem.builder()
                .claimId(claim.getId())
                .sourceType("upload")
                .filename(filename)
                .mediaType(mediaType)
                .gcsPath(objectPath)
                .rawContent(null)
                .fileHash(fileHash)
                .fileSize(file.getSize())
                .processingStatus("pending")
                .build();
        evidence = evidenceRepository.save(evidence);

        // Bug fix: transition synchronously so the global banner reflects work-in-progress
        // before the async pipeline starts.
        claim.setStatus(Claim.ClaimStatus.EXTRACTING);
        claim.setAnalysisStage("extracting");
        claim.setAnalysisProgressPct(5);
        claimRepository.saveAndFlush(claim);

        // Process in background. Bill extraction/synthesis to the claim OWNER
        // (not the caller) — on a VSO upload via X-View-As the doc belongs to
        // the owner's claim, so extraction quota and ML-cost attribution must
        // follow the data, not the uploader. On the own-claim path ca.claim
        // .getUserId() == user.getId(), so this is identity-preserving for
        // existing flows. Phase D will revisit chat-side billing separately.
        pipelineService.processEvidence(evidence.getId(), ca.claim.getUserId());

        return toEvidenceResponse(evidence);
    }

    // JSON-body evidence upload (the Flutter app's lane) was removed 2026-08-02
    // with that frontend (docs/maintenance/dead-code-audit-2026-08-02.md) —
    // multipart above is the only upload lane; typed statements go through
    // POST /api/claim/quick-add.


    // --- Quick Add ---

    /**
     * Create a short piece of text evidence (e.g. a condition name or
     * one-line symptom hint) without going through the file-upload UX.
     * Modeled as an EvidenceItem with source_type="quick_add" so it feeds
     * the extraction + synthesis pipeline like any other statement.
     */
    @PostMapping("/quick-add")
    @ResponseStatus(HttpStatus.CREATED)
    public EvidenceResponse quickAdd(@RequestBody QuickAddRequest req,
                                     HttpServletRequest request) {
        User user = getUser(request);
        // Quick-add text statements are FREE, exactly like file uploads — same
        // pipeline, zero cost difference. The paid AI processing stays gated
        // downstream (AnalysisScheduler + /analyze).
        ClaimAndAccess ca = resolveClaimAndAccess(user, request, AccessScope.UPLOAD_DOCS);
        Claim claim = ca.claim;

        if (req.getText() == null || req.getText().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text is required");
        }
        // Quick-add is for short statements; real documents go through upload.
        if (req.getText().length() > QUICK_ADD_MAX_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Statement is too long. Maximum is " + QUICK_ADD_MAX_CHARS + " characters.");
        }

        String filename = "quick-add-" + System.currentTimeMillis() + ".txt";
        EvidenceItem evidence = EvidenceItem.builder()
                .claimId(claim.getId())
                .sourceType("quick_add")
                .filename(filename)
                .rawContent(req.getText())
                .processingStatus("pending")
                .build();
        evidence = evidenceRepository.save(evidence);

        // Bill to claim owner; see processEvidence comment in uploadEvidence above.
        pipelineService.processEvidence(evidence.getId(), ca.claim.getUserId());

        return toEvidenceResponse(evidence);
    }

    // --- Evidence List ---

    @GetMapping("/evidence")
    public List<EvidenceResponse> getEvidence(HttpServletRequest request) {
        User user = getUser(request);
        ClaimAndAccess ca = resolveClaimAndAccess(user, request, AccessScope.VIEW_DOCS);
        Claim claim = ca.claim;
        return evidenceRepository.findByClaimIdOrderByCreatedAt(claim.getId()).stream()
                .map(this::toEvidenceResponse)
                .toList();
    }

    // --- Evidence Download ---

    /**
     * Stream the original uploaded file (or raw text for pasted/quick-add
     * entries) back to the client. Sets Content-Disposition so browsers
     * trigger a download rather than inline-render.
     */
    @GetMapping("/evidence/{evidenceId}/download")
    public ResponseEntity<byte[]> downloadEvidence(@PathVariable Long evidenceId,
                                                   HttpServletRequest request) {
        User user = getUser(request);
        ClaimAndAccess ca = resolveClaimAndAccess(user, request, AccessScope.VIEW_DOCS);
        Claim claim = ca.claim;
        EvidenceItem evidence = evidenceRepository.findByIdAndClaimId(evidenceId, claim.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Evidence not found"));

        // Serve from GCS when the row is GCS-backed; fall back to legacy
        // raw_content base64/text rows. Both paths go through the one accessor.
        byte[] bytes;
        try {
            bytes = documentStorageService.loadFileBytes(evidence);
        } catch (Exception e) {
            log.error("Failed to load evidence {} bytes (gcs={}): {}",
                    evidenceId, evidence.getGcsPath(), e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "download_failed");
        }
        if (bytes == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No content");
        }

        String fn = evidence.getFilename() != null ? evidence.getFilename() : ("evidence-" + evidenceId);
        // Normalize at serve time too: legacy rows may carry pre-allowlist
        // mediaType values, and a user-controlled Content-Type rendered on the
        // app origin is a stored-XSS vector. nosniff stops browsers second-
        // guessing octet-stream back into something active.
        String mt = documentStorageService.normalizeMediaType(
                evidence.getMediaType() != null ? evidence.getMediaType()
                        : (evidence.getGcsPath() != null ? "application/octet-stream" : "text/plain"));
        return ResponseEntity.ok()
                .header("Content-Type", mt)
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "attachment; filename=\"" + fn.replace("\"", "") + "\"")
                .body(bytes);
    }

    // --- Evidence Facts (extracted facts, AKA atoms in developer-speak) ---

    /**
     * Return every extracted fact sourced from a specific document.
     * Frontend calls this the "View facts" action on the evidence row.
     */
    @GetMapping("/evidence/{evidenceId}/facts")
    public List<AtomDto> getEvidenceFacts(@PathVariable Long evidenceId,
                                          HttpServletRequest request) {
        User user = getUser(request);
        ClaimAndAccess ca = resolveClaimAndAccess(user, request, AccessScope.VIEW_DOCS);
        Claim claim = ca.claim;
        // Ensure the evidence belongs to this user's claim before exposing atoms.
        evidenceRepository.findByIdAndClaimId(evidenceId, claim.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Evidence not found"));

        // Mission 5a: show the LIVE atoms for this document. If the doc was
        // re-extracted, its prior atoms are superseded (kept for citation history)
        // and should not appear in the per-document atom listing.
        return atomRepository.findByEvidenceIdAndSupersededByIsNullOrderByCreatedAtAsc(evidenceId).stream()
                .map(a -> new AtomDto(
                        a.getType(),
                        a.getValue(),
                        a.getSource(),
                        a.getConfidence(),
                        a.getTimestamp()))
                .toList();
    }

    // --- Evidence Delete ---

    /**
     * P1-19 (Phase G1) — DELETE is owner-only ({@code DELETE_DOCS}), no longer
     * granted by the share's canUploadDocs flag: a viewer allowed to add documents
     * must never be able to hard-delete the owner's evidence (row + atoms + GCS
     * object). Viewers keep add-type mutations (upload/quick-add) under
     * {@code UPLOAD_DOCS}; the own-claim path is unchanged (owner full access).
     */
    @DeleteMapping("/evidence/{evidenceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEvidence(@PathVariable Long evidenceId, HttpServletRequest request) {
        User user = getUser(request);
        ClaimAndAccess ca = resolveClaimAndAccess(user, request, AccessScope.DELETE_DOCS);
        Claim claim = ca.claim;
        EvidenceItem evidence = evidenceRepository.findByIdAndClaimId(evidenceId, claim.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Evidence not found"));

        // DB rows first, in one transaction (atoms, medical events, chunks, the
        // evidence row, the re-analysis flags). This used to run the derived
        // atom delete straight from the controller — no transaction — and every
        // delete of an analyzed document failed with "No EntityManager with
        // actual transaction available" (2026-09-13). A failure here now leaves
        // the stored file untouched, so a row never points at a missing object.
        String gcsPath = evidence.getGcsPath();
        evidenceDeletionService.deleteEvidenceRows(claim, evidence);

        // Then the file, best-effort. A failure leaks one orphan object, which is
        // the harmless direction; it is logged for cleanup.
        if (gcsPath != null) {
            try {
                documentStorageService.deleteFromGcs(gcsPath);
            } catch (Exception e) {
                log.warn("Failed to delete GCS file {} for evidence {}: {}", gcsPath, evidenceId, e.getMessage());
            }
        }
    }

    // --- Conditions ---

    @GetMapping("/conditions")
    public List<ConditionResponse> getConditions(HttpServletRequest request) {
        User user = getUser(request);
        ClaimAndAccess ca = resolveClaimAndAccess(user, request, AccessScope.VIEW_ANALYSIS);
        Claim claim = ca.claim;
        boolean includeSuperseded = "true".equals(request.getParameter("include_superseded"));
        return conditionRepository.findByClaimId(claim.getId()).stream()
                .filter(c -> includeSuperseded || c.getSupersededBy() == null)
                .map(this::toConditionResponse)
                .toList();
    }

    // --- Gaps ---

    /**
     * Flatten every ACTIVE condition's `gaps` JSON field into a single
     * claim-level envelope so the Action Plan UI can render, sort, and
     * prioritize them without doing N round-trips.
     *
     * P0-1 contract — the pipeline persists gaps keyed
     * title/description/rating_impact/how_to_get_it/estimated_time/
     * estimated_cost_usd (EvidenceGapAnalyzer writes them; GapValidationAgent
     * preserves them). This endpoint maps those to the UI field names:
     *   label   ← title
     *   why     ← description (+ " (VASRD: <vasrd_reference>)" when present)
     *   suggest ← how_to_get_it
     *   impact  ← rating_impact (legacy rows: impact)
     * plus pass-through: priority (inferred when absent: high if impact
     * parses to ≥ +20%, medium if < +20% or type "In-Service", else low),
     * humanized type ("nexus_letter" → "Nexus letter"), status (default
     * "open"), index (position within the condition's gaps list — the stable
     * handle for future status updates), triadLeg, targetRating,
     * estimatedTime, estimatedCostUsd.
     *
     * Response envelope: { "gaps": [ {...}, ... ], "gapAnalysisPending": bool }.
     * gapAnalysisPending flips true when ANY active condition has a null gaps
     * field — mid-re-analysis the generation flip precedes the gap writes, so
     * clients must render "re-checking your next steps" instead of a false
     * "all caught up".
     *
     * Item D (§6 A1) — for a FREE claim under free-analysis-tier=a1 the gap
     * stage never runs, so null gaps is that claim's PERMANENT state, not the
     * P0-5 transient: gapAnalysisPending stays FALSE (and gaps stays empty) so
     * the web renders the Pro upsell instead of an eternal "re-checking".
     */
    @GetMapping("/gaps")
    public Map<String, Object> getGaps(HttpServletRequest request) {
        User user = getUser(request);
        ClaimAndAccess ca = resolveClaimAndAccess(user, request, AccessScope.VIEW_ANALYSIS);
        Claim claim = ca.claim;
        boolean gapStageSkipped = gapStageSkippedForFreeTier(claim);
        List<Map<String, Object>> out = new ArrayList<>();
        boolean gapAnalysisPending = false;
        for (IdentifiedCondition c : conditionRepository.findByClaimId(claim.getId())) {
            if (c.getSupersededBy() != null) continue;
            List<Map<String, Object>> gs = c.getGaps();
            if (gs == null) {
                if (!gapStageSkipped) gapAnalysisPending = true;
                continue;
            }
            for (int i = 0; i < gs.size(); i++) {
                Map<String, Object> g = gs.get(i);
                String why = firstText(g, "description", "why");
                String vasrd = firstText(g, "vasrd_reference");
                if (!vasrd.isBlank()) {
                    why = (why + " (VASRD: " + vasrd + ")").trim();
                }
                String impact = firstText(g, "rating_impact", "impact");
                Object priority = g.get("priority");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("condId", c.getId());
                row.put("condName", c.getName());
                row.put("type", humanizeGapType(g.get("type")));
                row.put("label", firstText(g, "title", "label"));
                row.put("why", why);
                row.put("suggest", firstText(g, "how_to_get_it", "suggest"));
                row.put("impact", impact);
                row.put("priority", priority != null ? priority : inferPriority(impact, g.get("type")));
                // P1-6 — status via the ONE shared helper (UserGapStateService)
                // so this endpoint and /jobs' open_gaps counter can never
                // disagree about a gap's effective status.
                row.put("status", UserGapStateService.statusOf(g));
                row.put("index", i);
                row.put("triadLeg", g.get("triad_leg"));
                row.put("targetRating", g.get("target_rating"));
                row.put("estimatedTime", g.get("estimated_time"));
                row.put("estimatedCostUsd", g.get("estimated_cost_usd"));
                out.add(row);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("gaps", out);
        body.put("gapAnalysisPending", gapAnalysisPending);
        return body;
    }

    /** First non-blank string value among the given keys, else "". */
    private static String firstText(Map<String, Object> g, String... keys) {
        for (String k : keys) {
            Object v = g.get(k);
            if (v != null && !v.toString().isBlank()) return v.toString();
        }
        return "";
    }

    /**
     * Pipeline gap types are prompt-enum tokens ("nexus_letter"); render them
     * human. Tokens without underscores (legacy "Nexus"/"In-Service") pass
     * through unchanged.
     */
    private static String humanizeGapType(Object type) {
        if (type == null) return "Nexus";
        String t = type.toString();
        switch (t) {
            case "c_and_p_exam_request": return "C&P exam request";
            case "imo_independent_medical_opinion": return "Independent medical opinion";
            default:
                if (!t.contains("_")) return t;
                String spaced = t.replace('_', ' ');
                return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
        }
    }

    private static String inferPriority(String impact, Object type) {
        try {
            // impact strings look like "+10%" / "+50%" / ""
            if (impact != null && impact.startsWith("+")) {
                int pct = Integer.parseInt(impact.replaceAll("[^0-9]", ""));
                if (pct >= 20) return "high";
                if (pct > 0)   return "medium";
            }
        } catch (NumberFormatException ignored) {}
        if ("In-Service".equals(type)) return "medium";
        return "low";
    }

    // --- Gap status (P1-6 — "Mark done" / "Doesn't apply") ---

    /**
     * Set a gap's status. {@code gapIndex} is the position within the
     * condition's gaps list — the stable handle {@code GET /gaps} exposes as
     * {@code index}. Persists a durable {@code user_gap_state} row keyed by
     * the condition's identity fingerprint + the gap's (type, triad_leg) so
     * the decision survives re-analysis (which replaces the gap JSON and
     * renumbers indexes), AND stamps the status into the stored gap JSON so
     * {@code GET /gaps} reflects it immediately.
     *
     * <p>Body: {@code {"status": "resolved"|"dismissed"|"open"}}.
     * Responds 200 {@code {"ok": true, "status": s}}; 404 when the condition
     * or index doesn't exist or isn't the caller's claim's (superseded
     * prior-generation rows are 404 too — their indexes are meaningless);
     * 400 on an unknown status. Same access chokepoint + scope as
     * {@code GET /gaps} (VIEW_ANALYSIS): what you can see, you can check off.
     *
     * <p><b>P1-19 (Phase G1) — deliberately viewer-writable.</b> This is the ONE
     * claim-state mutation a VIEW_ANALYSIS viewer keeps: a VSO working the claim
     * checks gaps off ("Mark done" / "Doesn't apply") as the veteran gathers
     * evidence — that workflow is the point of analysis sharing. It is
     * non-destructive (statuses are reversible, the gap JSON itself is never
     * removed) and requires VIEW_ANALYSIS, not UPLOAD_DOCS. Everything
     * destructive (evidence DELETE, chat mutation tools) is owner-only.
     */
    @PatchMapping("/gaps/{condId}/{gapIndex}/status")
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> updateGapStatus(@PathVariable Long condId,
                                               @PathVariable int gapIndex,
                                               @RequestBody Map<String, String> body,
                                               HttpServletRequest request) {
        User user = getUser(request);
        ClaimAndAccess ca = resolveClaimAndAccess(user, request, AccessScope.VIEW_ANALYSIS);
        Claim claim = ca.claim;

        String status = body != null ? body.get("status") : null;
        if (!UserGapStateService.isValidStatus(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "status must be one of: open, resolved, dismissed");
        }

        IdentifiedCondition cond = conditionRepository.findById(condId)
                .filter(c -> c.getClaimId().equals(claim.getId()))
                .filter(c -> c.getSupersededBy() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Condition not found"));

        List<Map<String, Object>> gaps = cond.getGaps();
        if (gaps == null || gapIndex < 0 || gapIndex >= gaps.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Gap not found");
        }

        // Durable state first (fingerprint-keyed; survives re-analysis)...
        userGapStateService.upsert(claim.getId(), cond, gaps.get(gapIndex), status);

        // ...then the live gap JSON so GET /gaps reflects it immediately.
        // Rebuild list + touched map so JPA JSON dirty-tracking sees new values.
        List<Map<String, Object>> updated = new ArrayList<>(gaps.size());
        for (int i = 0; i < gaps.size(); i++) {
            if (i == gapIndex) {
                Map<String, Object> copy = new LinkedHashMap<>(gaps.get(i));
                copy.put("status", status);
                updated.add(copy);
            } else {
                updated.add(gaps.get(i));
            }
        }
        cond.setGaps(updated);
        conditionRepository.save(cond);

        return Map.of("ok", true, "status", status);
    }

    // --- Analyze ---

    @PostMapping("/analyze")
    public AnalyzeResponse analyzeClaim(HttpServletRequest request) {
        User user = getUser(request);
        requireActiveSubscription(user);
        // Auto-analysis runs via scheduler. This endpoint is retained as an
        // admin force-run only — regular users should not be able to spam it.
        // X-View-As is intentionally not honored here — admin-only, owner-only.
        if (!isAdmin(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Analysis runs automatically after uploads. Manual trigger is admin-only.");
        }
        Claim claim = getOrCreateActiveClaim(user);

        long evidenceCount = evidenceRepository.countByClaimId(claim.getId());
        if (evidenceCount == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No evidence to analyze.");
        }

        // Mission 5a: gate analysis on LIVE atoms (superseded atoms from a
        // re-extracted document are not fresh synthesizable evidence).
        long atomCount = atomRepository.countByClaimIdAndSupersededByIsNull(claim.getId());
        if (atomCount == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No atoms extracted yet. Wait for document processing to complete.");
        }

        if (Claim.ClaimStatus.SYNTHESIZING == claim.getStatus()) {
            return AnalyzeResponse.builder()
                    .status("already_running")
                    .message("Analysis is already in progress.")
                    .build();
        }

        claim.setStatus(Claim.ClaimStatus.SYNTHESIZING);
        claim.setSynthesisNeeded(true);
        claimRepository.save(claim);

        // Run in background
        new Thread(() -> {
            try {
                pipelineService.runFullPipeline(claim.getId(), user.getId());
            } catch (Exception e) {
                claim.setStatus(Claim.ClaimStatus.ERROR);
                claimRepository.save(claim);
            }
        }).start();

        return AnalyzeResponse.builder()
                .status("started")
                .message("Analysis started with " + atomCount + " atoms from " + evidenceCount + " documents.")
                .atomCount(atomCount)
                .evidenceCount(evidenceCount)
                .build();
    }

    // --- Debug: Individual Pipeline Steps ---

    @PostMapping("/debug/re-extract/{evidenceId}")
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> debugReExtract(@PathVariable Long evidenceId, HttpServletRequest request) {
        User user = getUser(request);
        Claim claim = getOrCreateActiveClaim(user);
        EvidenceItem evidence = evidenceRepository.findByIdAndClaimId(evidenceId, claim.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Evidence not found"));

        // Delete existing atoms for this evidence
        atomRepository.deleteByEvidenceId(evidenceId);
        evidence.setProcessingStatus("pending");
        evidenceRepository.save(evidence);

        // Run extraction synchronously so we can return the result
        pipelineService.processEvidence(evidenceId, user.getId());

        // Reload to get updated state
        evidence = evidenceRepository.findById(evidenceId).orElse(evidence);
        // Mission 5a: report the LIVE atom count for this evidence (a re-extraction
        // supersedes the prior generation's atoms; the veteran should see the
        // current count, not current + retired).
        long atomCount = atomRepository.countByEvidenceIdAndSupersededByIsNull(evidenceId);

        return Map.of(
                "status", evidence.getProcessingStatus(),
                "evidence_id", evidenceId,
                "atom_count", atomCount,
                "ai_summary", evidence.getAiSummary() != null ? evidence.getAiSummary() : "",
                "ai_extracted_data", evidence.getAiExtractedData() != null ? evidence.getAiExtractedData() : Map.of()
        );
    }

    @PostMapping("/debug/synthesis")
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> debugSynthesis(HttpServletRequest request) {
        User user = getUser(request);
        Claim claim = getOrCreateActiveClaim(user);

        // Mission 5a: gate on LIVE atoms (superseded atoms from re-extracted docs
        // don't represent synthesizable evidence).
        long atomCount = atomRepository.countByClaimIdAndSupersededByIsNull(claim.getId());
        if (atomCount == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No atoms to synthesize.");
        }

        // Clear existing conditions
        conditionRepository.deleteByClaimId(claim.getId());

        // Run synthesis synchronously
        String result = pipelineService.runSynthesisOnly(claim.getId(), user.getId());
        long condCount = conditionRepository.findByClaimId(claim.getId()).size();

        return Map.of(
                "status", "complete",
                "atom_count", atomCount,
                "conditions_created", condCount,
                "result", result
        );
    }

    @PostMapping("/debug/gap-analysis")
    public Map<String, Object> debugGapAnalysis(HttpServletRequest request) {
        User user = getUser(request);
        Claim claim = getOrCreateActiveClaim(user);

        // Mission 5b — gate on the ACTIVE generation (a re-analysis's superseded
        // rows must not satisfy the "you have conditions" precondition).
        long condCount = conditionRepository.findByClaimIdAndSupersededByIsNull(claim.getId()).size();
        if (condCount == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No conditions to analyze. Run synthesis first.");
        }

        // Run gap analysis synchronously
        String result = pipelineService.runGapAnalysisOnly(claim.getId(), user.getId());

        return Map.of(
                "status", "complete",
                "conditions_analyzed", condCount,
                "result", result
        );
    }

    @PostMapping("/debug/post-process")
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> debugPostProcess(HttpServletRequest request) {
        User user = getUser(request);
        Claim claim = getOrCreateActiveClaim(user);
        conditionPostProcessService.postProcess(claim.getId());
        long activeCount = conditionRepository.findByClaimId(claim.getId()).stream()
                .filter(c -> c.getSupersededBy() == null).count();
        long supersededCount = conditionRepository.findByClaimId(claim.getId()).stream()
                .filter(c -> c.getSupersededBy() != null).count();
        return Map.of("status", "complete", "active_conditions", activeCount, "superseded", supersededCount);
    }

    @GetMapping("/debug/atoms")
    public Map<String, Object> debugAtoms(HttpServletRequest request) {
        User user = getUser(request);
        Claim claim = getOrCreateActiveClaim(user);

        // Mission 5a: debug dump reflects the LIVE atom set (what analysis uses).
        var atoms = atomRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
        return Map.of(
                "claim_id", claim.getId(),
                "atom_count", atoms.size(),
                "atoms", atoms.stream().map(a -> Map.of(
                        "id", a.getId(),
                        "type", a.getType(),
                        "value", a.getValue(),
                        "source", a.getSource(),
                        "confidence", a.getConfidence() != null ? a.getConfidence() : 0.0,
                        "date", a.getTimestamp() != null ? a.getTimestamp() : ""
                )).toList()
        );
    }

    // --- Chat ---

    @PostMapping("/chat")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, MessageResponse> chat(@RequestBody ChatRequest req, HttpServletRequest request) {
        User user = getUser(request);
        ClaimAndAccess ca = resolveClaimAndAccess(user, request, AccessScope.CHAT);
        Claim claim = ca.claim;

        // Delegate to ChatService which handles thread, billing, agent, and synthesisNeeded.
        Map<String, IntakeMessage> result =
                chatService.sendMessage(user, claim.getId(), req.getMessage());

        return Map.of(
                "veteran_message", toMessageResponse(result.get("veteran_message")),
                "assistant_message", toMessageResponse(result.get("assistant_message"))
        );
    }

    // --- Messages ---

    @GetMapping("/messages")
    public List<MessageResponse> getMessages(HttpServletRequest request) {
        User user = getUser(request);
        ClaimAndAccess ca = resolveClaimAndAccess(user, request, AccessScope.VIEW_DOCS);
        Claim claim = ca.claim;
        return chatService.listMessages(user, claim.getId()).stream()
                .map(this::toMessageResponse)
                .toList();
    }

    // --- Background jobs snapshot (for the cross-screen actions bar) ---

    @GetMapping("/jobs")
    public Map<String, Object> jobs(HttpServletRequest request) {
        User user = getUser(request);
        ClaimAndAccess ca = resolveClaimAndAccess(user, request, AccessScope.VIEW_DOCS);
        Claim claim = ca.claim;

        List<EvidenceItem> evidence = evidenceRepository.findByClaimIdOrderByCreatedAt(claim.getId());

        List<Map<String, Object>> extractions = new ArrayList<>();
        int pending = 0;
        int processing = 0;
        int processed = 0;
        int errored = 0;
        for (EvidenceItem e : evidence) {
            String status = e.getProcessingStatus() != null ? e.getProcessingStatus() : "pending";
            switch (status) {
                case "pending" -> pending++;
                case "processing" -> processing++;
                case "processed" -> processed++;
                case "error" -> errored++;
                default -> {}
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("evidence_id", e.getId());
            row.put("filename", e.getFilename());
            row.put("status", status);
            row.put("queued_at", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
            extractions.add(row);
        }

        Map<String, Object> synthesis = new LinkedHashMap<>();
        synthesis.put("state", Boolean.TRUE.equals(claim.getSynthesisInProgress()) ? "running" : "idle");
        synthesis.put("last_run_at", claim.getLastSynthesisAt() != null ? claim.getLastSynthesisAt().toString() : null);
        synthesis.put("last_model", claim.getLastSynthesisModel());

        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("state", Boolean.TRUE.equals(claim.getGapAnalysisInProgress()) ? "running" : "idle");
        gap.put("last_run_at", claim.getLastGapAnalysisAt() != null ? claim.getLastGapAnalysisAt().toString() : null);
        gap.put("last_model", claim.getLastGapAnalysisModel());

        int activeCount = processing + pending
                + (Boolean.TRUE.equals(claim.getSynthesisInProgress()) ? 1 : 0)
                + (Boolean.TRUE.equals(claim.getGapAnalysisInProgress()) ? 1 : 0);

        // Aggregate facts — shown by the banner when idle so the user always
        // has fresh, meaningful information to look at. Mission 5a: LIVE atoms only.
        // Mission 5b: ACTIVE-generation conditions only (a re-analysis supersedes
        // the prior generation; the banner must count the current one).
        long atomCount = atomRepository.countByClaimIdAndSupersededByIsNull(claim.getId());
        List<IdentifiedCondition> conditions = conditionRepository.findByClaimIdAndSupersededByIsNull(claim.getId());
        int conditionCount = conditions.size();
        int openGaps = 0;
        // P0-5: an active condition with a null gaps field means the generation
        // flipped but its gap analysis hasn't landed — the polling banner must
        // show "re-checking", never a false "all caught up". Item D (§6 A1):
        // EXCEPT for a free claim whose gap stage never runs — null gaps is its
        // permanent state and must not read as pending (see getGaps).
        boolean gapStageSkipped = gapStageSkippedForFreeTier(claim);
        boolean gapAnalysisPending = false;
        for (IdentifiedCondition c : conditions) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> gaps = (List<Map<String, Object>>) c.getGaps();
            if (gaps != null) {
                for (Map<String, Object> g : gaps) {
                    // P1-6 — the ONE shared open-gap predicate (also drives the
                    // status /gaps serializes), so both endpoints always agree.
                    if (UserGapStateService.isOpen(g)) {
                        openGaps++;
                    }
                }
            } else if (!gapStageSkipped) {
                gapAnalysisPending = true;
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("claim_id", claim.getId());
        // P0-4: claim-level terminal signals so a single polling endpoint can
        // drive the banner end-to-end (ERROR + message, terminal completion).
        body.put("status", claim.getStatus() != null ? claim.getStatus().name() : "DRAFT");
        body.put("analysis_message", claim.getAnalysisMessage());
        body.put("gap_analysis_pending", gapAnalysisPending);
        body.put("active_count", activeCount);
        body.put("summary", Map.of(
                "documents", evidence.size(),
                "atoms", atomCount,
                "conditions", conditionCount,
                "open_gaps", openGaps));
        body.put("extraction", Map.of(
                "pending", pending,
                "processing", processing,
                "processed", processed,
                "errored", errored,
                "items", extractions));
        body.put("synthesis", synthesis);
        body.put("gap_analysis", gap);
        return body;
    }

    // --- Pipeline Metrics ---

    @GetMapping("/pipeline-metrics")
    public List<PipelineMetrics> getPipelineMetrics(HttpServletRequest request) {
        User user = getUser(request);
        ClaimAndAccess ca = resolveClaimAndAccess(user, request, AccessScope.VIEW_DOCS);
        Claim claim = ca.claim;
        return pipelineMetricsRepository.findByClaimIdOrderByRunTimestampDesc(claim.getId());
    }

    // --- Helpers ---

    /**
     * Tiny carrier that pairs a resolved {@link Claim} with its {@link ClaimAccess}.
     * Used so callers can interrogate {@code access.isOwner()} for subscription checks.
     */
    private record ClaimAndAccess(Claim claim, ClaimAccess access) {}

    /**
     * Resolves the target claim and verifies the scope in one step.
     *
     * <p>If {@code X-View-As} header is absent: uses the caller's own claim
     * (auto-creating it if necessary). Scope is NOT enforced on the own-claim
     * path — pre-Phase-C behavior is preserved exactly (owner always has full
     * access to their own claim with no subscription gate beyond what was already
     * there before Phase C).  Returns a synthetic full-owner {@link ClaimAccess}
     * so downstream callers can detect the owner path via {@code access.isOwner()}.
     *
     * <p>If the header is present: calls
     * {@link ClaimAccessService#resolveIfPresent} (which throws 400/403/404 as
     * appropriate), then asserts the given scope. NEVER auto-creates on this path.
     */
    private ClaimAndAccess resolveClaimAndAccess(User user, HttpServletRequest request, AccessScope scope) {
        Optional<ClaimAccess> viewAs = claimAccessService.resolveIfPresent(user, request);
        if (viewAs.isPresent()) {
            // X-View-As header path: enforce scope strictly.
            ClaimAccess access = viewAs.get();
            claimAccessService.assertScope(access, scope, user);
            Claim claim = claimRepository.findById(access.claimId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "claim_not_found"));
            return new ClaimAndAccess(claim, access);
        }
        // No header: own-claim path — may auto-create, owner always has full access.
        // Scope is NOT checked here to preserve pre-Phase-C behavior identically.
        Claim claim = getOrCreateActiveClaim(user);
        ClaimAccess ownerAccess = new ClaimAccess(claim.getId(), user.getId(), true, true, true);
        return new ClaimAndAccess(claim, ownerAccess);
    }

    private User getUser(HttpServletRequest request) {
        User user = (User) request.getAttribute(SecurityConfig.USER_ATTRIBUTE);
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        return user;
    }

    /** Admin escape hatch used for debug endpoints and manual pipeline reruns. */
    private boolean isAdmin(User user) {
        return adminCheck.isAdmin(user);
    }

    /**
     * Gate paid features behind an active $100/year subscription.
     * Frontend recognizes 402 and pops the paywall.
     */
    private void requireActiveSubscription(User user) {
        if (subscriptionAccess.isPro(user)) return;
        throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "subscription_required");
    }

    /**
     * Item D (§6 A1): true when the gap stage will NEVER run for this claim —
     * the free analysis tier is live ({@code free-analysis-tier=a1}) and the
     * claim's OWNER has no active subscription (gap analysis stays Pro, so
     * AnalysisScheduler never arms it). The claim's conditions then carry
     * {@code gaps == null} permanently, which must read as "not on this plan"
     * (gapAnalysisPending=false, empty gaps list), NOT the P0-5 "re-checking
     * your next steps" transient. Keyed to the OWNER, not the viewer, so a Pro
     * viewer of a shared free claim isn't shown a pending state that will
     * never resolve. Flag off ⇒ always false (pre-Phase-D behavior exactly).
     */
    private boolean gapStageSkippedForFreeTier(Claim claim) {
        if (subscriptionProperties == null || !subscriptionProperties.isFreeAnalysisA1()) {
            return false;
        }
        return userRepository.findById(claim.getUserId())
                .map(u -> !subscriptionAccess.isPro(u))
                .orElse(true);
    }

    /**
     * Get the user's active claim, or auto-create one if none exists.
     */
    Claim getOrCreateActiveClaim(User user) {
        List<Claim> claims = claimRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        if (!claims.isEmpty()) {
            return claims.get(0);
        }
        // Auto-create an INITIAL claim for this user
        Claim claim = Claim.builder()
                .userId(user.getId())
                .claimType(Claim.ClaimType.INITIAL)
                .status(Claim.ClaimStatus.DRAFT)
                .build();
        return claimRepository.save(claim);
    }

    ClaimResponse toClaimResponse(Claim c) {
        return ClaimResponse.builder()
                .id(c.getId())
                .claimType(c.getClaimType() != null ? c.getClaimType().name() : "INITIAL")
                .status(c.getStatus() != null ? c.getStatus().name() : "DRAFT")
                .synthesisNeeded(Boolean.TRUE.equals(c.getSynthesisNeeded()))
                .evidenceCount(evidenceRepository.countByClaimId(c.getId()))
                // Mission 5b — ACTIVE-generation count (superseded prior-gen rows excluded).
                .conditionCount(conditionRepository.findByClaimIdAndSupersededByIsNull(c.getId()).size())
                .atomCount(atomRepository.countByClaimIdAndSupersededByIsNull(c.getId()))
                .createdAt(serializeInstant(c.getCreatedAt()))
                .updatedAt(serializeInstant(c.getUpdatedAt()))
                .analysisMessage(c.getAnalysisMessage())
                .analysisStage(c.getAnalysisStage())
                .analysisProgressPct(c.getAnalysisProgressPct())
                .lastAnalyzedAt(c.getLastAnalyzedAt())
                .build();
    }

    private EvidenceResponse toEvidenceResponse(EvidenceItem e) {
        return EvidenceResponse.builder()
                .id(e.getId())
                .sourceType(e.getSourceType())
                .filename(e.getFilename())
                .aiClassification(e.getAiClassification())
                .aiExtractedData(e.getAiExtractedData())
                .aiSummary(e.getAiSummary())
                .processingStatus(e.getProcessingStatus())
                .processingMessage(e.getProcessingMessage())
                .createdAt(serializeInstant(e.getCreatedAt()))
                .build();
    }

    private ConditionResponse toConditionResponse(IdentifiedCondition c) {
        return ConditionResponse.builder()
                .id(c.getId())
                .claimId(c.getClaimId())
                .name(c.getName())
                .vasrdCode(c.getVasrdCode())
                .bodySystem(c.getBodySystem())
                .triadDiagnosis(c.getTriadDiagnosis())
                .triadInService(c.getTriadInService())
                .triadNexus(c.getTriadNexus())
                .isPresumptive(Boolean.TRUE.equals(c.getIsPresumptive()))
                .presumptiveBasis(c.getPresumptiveBasis())
                .secondaryTo(c.getSecondaryTo())
                // Nulls pass through (P1-5): an unrated condition must reach the
                // client as ABSENT (jackson non_null), not a fabricated 0.
                .estimatedRating(c.getEstimatedRating())
                .ratingRationale(c.getRatingRationale())
                // Rating honesty: the "needs <objective measure>" tag (null unless the
                // code is mapped AND the measure is absent). The tempered confidence
                // already rides c.getConfidence() above — assessRatingEvidence wrote it.
                .ratingEvidenceNote(c.getRatingEvidenceNote())
                .confidence(c.getConfidence())
                .gaps(c.getGaps() != null ? c.getGaps() : Collections.emptyList())
                .whatIfScenarios(c.getWhatIfScenarios() != null ? c.getWhatIfScenarios() : Collections.emptyList())
                .pyramidGroup(c.getPyramidGroup())
                .pyramidReason(c.getPyramidReason())
                // Pyramiding grouped view: the effective-member marker + the group's
                // effective rating (both null unless assignPyramidingGroups grouped this
                // condition). Additive; nulls pass through (non_null).
                .pyramidPrimary(c.getPyramidPrimary())
                .pyramidGroupRating(c.getPyramidGroupRating())
                // "Don't include in my claim" (owner-set, reversible). The list ALWAYS
                // returns excluded conditions so the web can show them in the "Not filing"
                // section; only the combined-rating math (RatingController) drops them.
                .excludedFromClaim(Boolean.TRUE.equals(c.getExcludedFromClaim()))
                .build();
    }

    private MessageResponse toMessageResponse(IntakeMessage m) {
        return MessageResponse.builder()
                .id(m.getId())
                .role(m.getRole())
                .content(m.getContent())
                .extractedData(m.getExtractedData())
                .createdAt(serializeInstant(m.getCreatedAt()))
                .build();
    }

    private String serializeInstant(Instant instant) {
        return instant != null ? instant.toString() : "";
    }
}
