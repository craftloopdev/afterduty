package com.afterduty.service.extraction;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.config.LlmRoutingProperties;
import com.afterduty.dto.AtomDto;
import com.afterduty.model.EvidenceItem;
import com.afterduty.model.MedicalEvent;
import com.afterduty.service.DocumentStorageService;
import com.afterduty.service.llm.LlmJobRequest;
import com.afterduty.service.llm.LlmJobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Single-pass structured document extraction (Mission B).
 *
 * <p>Collapses the legacy five per-document passes (diagnosis, medication,
 * service-record, generic-atom, segmentation+event) into <b>one</b> schema'd
 * structured-output Gemini call per document. The model returns a single
 * {@code DocFacts} JSON object whose sub-sections carry the <em>exact field
 * shapes</em> the legacy per-pass parsers consume — so each <em>atom</em> section
 * (diagnoses, medications, service-record, generic atoms) is persisted by
 * delegating to the very same parser methods, producing atoms with identical
 * type/value/source/confidence/date/provenance to the multi-pass output.
 *
 * <p><b>Where this differs from multi-pass (intentionally):</b>
 * <ul>
 *   <li>The {@code events[]} section is persisted as {@link MedicalEvent} rows via
 *       the segmentation parser, exactly as the legacy SEGMENTS stage did — so
 *       cross-event aggregation reads the same {@code medical_events} table.</li>
 *   <li>The legacy <em>per-event</em> atom pass (EXTRACTING_EVENTS, which fanned a
 *       second LLM call per MedicalEvent and emitted {@code ai:extraction-event}
 *       atoms) is NOT re-run here. The single model call emits the {@code atoms[]}
 *       section directly, so per-event atoms are not separately re-extracted.</li>
 * </ul>
 * The atom sections are therefore byte-identical; the event handling is
 * MedicalEvent-equivalent but does not reproduce the per-event atom pass.
 *
 * <p><b>Abstention is first-class.</b> The schema includes per-section
 * {@code *_not_found} booleans (a readable doc that legitimately contains no
 * meds/diagnoses ⇒ empty lists, not an error) and a top-level
 * {@code unreadable_or_unsupported} flag with a {@code reason}. An unreadable
 * document is surfaced as {@code processing_status=error} with a plain-language
 * {@code processing_message} — never a silent empty extraction.
 *
 * <p><b>Multimodal.</b> For PDF/image uploads the original bytes ride as Gemini
 * {@code inlineData} parts (loaded via {@link DocumentStorageService#loadFileBytes}).
 * Documents too large to inline under the Vertex payload ceiling fall back to the
 * legacy text path (decoded text / {@code raw_content}) with an honest
 * processing message recorded on success. Plain text / quick-add evidence always
 * uses the text path.
 */
@Service
public class SinglePassExtractionService {

    private static final Logger log = LoggerFactory.getLogger(SinglePassExtractionService.class);

    /**
     * createdBy provenance tags for the four atom-producing sections — identical to
     * the legacy per-pass tags so atom origin is unchanged. There is deliberately
     * NO event-provenance tag here: the events[] section is persisted as
     * MedicalEvent rows (not atoms), so single-pass never emits {@code
     * ai:extraction-event} atoms the way the legacy EXTRACTING_EVENTS pass did.
     */
    static final String CREATED_BY_DIAGNOSIS      = "ai:extraction-diagnosis";
    static final String CREATED_BY_MEDICATION     = "ai:extraction-medication";
    static final String CREATED_BY_SERVICE_RECORD = "ai:extraction-service-record";
    static final String CREATED_BY_ATOM           = "ai:extraction-atom";

    /**
     * Extraction prompt version (Mission 5a). Part of every document's
     * {@code extract_key}. <b>Bump this when {@link #SYSTEM_PROMPT} changes in a
     * way that should re-extract every document</b> — incrementing it shifts the
     * computed key for every doc, so the next run re-extracts them all (old atoms
     * superseded, new atoms persisted) by design. Starts at "1".
     *
     * <p>Exposed via {@link com.afterduty.config.PromptVersionRegistry} (Increment
     * 8 eval harness) — bumping it fails the offline snapshot gate until a live-eval
     * run is acknowledged.
     */
    public static final String PROMPT_VERSION = "1";

    /**
     * Extraction output-schema version (Mission 5a). Part of every document's
     * {@code extract_key}. <b>Bump this when {@link #docFactsSchema()} changes
     * shape</b> (new/removed sections, changed field set) so all documents
     * re-extract under the new schema. Starts at "1". Bumping either PROMPT or
     * SCHEMA version invalidates all docs — that is the intended global-reextract
     * lever.
     *
     * <p>Exposed via {@link com.afterduty.config.PromptVersionRegistry} (Increment
     * 8 eval harness).
     */
    public static final String SCHEMA_VERSION = "2"; // 2: top-level `required` added (empty-extraction fix)

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final DocumentStorageService documentStorageService;
    private final DiagnosisExtractorService diagnosisExtractorService;
    private final MedicationExtractorService medicationExtractorService;
    private final ServiceRecordExtractorService serviceRecordExtractorService;
    private final GeminiExtractionService geminiExtractionService;
    private final EventSegmentationAgent eventSegmentationAgent;
    private final LlmRoutingProperties routingProperties;

    @Value("${va-claim.extraction.max-inline-mb:18}")
    private int maxInlineMb;

    @Value("${va-claim.gemini.thinking-budget:8192}")
    private int thinkingBudget;

    /**
     * Last-resort extraction model id used in the {@code extract_key} when no
     * {@code va-claim.llm.purposes.extraction_doc.model} is configured — mirrors
     * the router's {@code vertex-gemini} default so the key reflects the model
     * that would actually run. Env-overridable for parity with the route config.
     */
    @Value("${va-claim.llm.purposes.extraction_doc.model:${ROUTE_EXTRACTION_DOC_MODEL:gemini-3.1-pro-preview}}")
    private String extractionDocModelFallback;

    public SinglePassExtractionService(DocumentStorageService documentStorageService,
                                       DiagnosisExtractorService diagnosisExtractorService,
                                       MedicationExtractorService medicationExtractorService,
                                       ServiceRecordExtractorService serviceRecordExtractorService,
                                       GeminiExtractionService geminiExtractionService,
                                       EventSegmentationAgent eventSegmentationAgent,
                                       LlmRoutingProperties routingProperties) {
        this.documentStorageService = documentStorageService;
        this.diagnosisExtractorService = diagnosisExtractorService;
        this.medicationExtractorService = medicationExtractorService;
        this.serviceRecordExtractorService = serviceRecordExtractorService;
        this.geminiExtractionService = geminiExtractionService;
        this.eventSegmentationAgent = eventSegmentationAgent;
        this.routingProperties = routingProperties;
    }

    // -------------------------------------------------------------------------
    // System prompt
    // -------------------------------------------------------------------------

    private static final String SYSTEM_PROMPT = """
            You are a VA disability claims evidence extraction AI. Read the supplied document
            ONCE and return a single JSON object (DocFacts) capturing everything relevant to a
            VA disability claim. Match the schema EXACTLY.

            Top-level fields:
            - doc_type: one of health_summary, imo_letter, dd214, dbq, personal_statement,
              lab_report, imaging_report, prescription_record, clinical_note, other
            - doc_date: the document's primary date (YYYY-MM-DD) or null
            - unreadable_or_unsupported: true ONLY if the document cannot be read at all
              (corrupt, blank, password-protected, an unsupported binary, or contains no
              legible content). When true, set "reason" to a short plain-language explanation
              the veteran can understand, and leave all the lists empty.
            - reason: plain-language reason string when unreadable_or_unsupported is true; else null

            Sections (each has a *_not_found boolean — set it true when the section was searched
            but legitimately contains nothing; that is NOT an error, just an empty list):

            - diagnoses[] / diagnoses_not_found: each {diagnosis_name, icd10_code, date_diagnosed
              (YYYY-MM-DD or null), diagnosing_provider, severity (mild|moderate|severe|null),
              status (active|resolved|chronic), related_conditions, functional_limitations}
            - medications[] / medications_not_found: each {drug_name, dosage, frequency, route,
              prescriber, start_date, end_date, purpose, changes}
            - service_records / service_record_not_found: a SINGLE object {rank, pay_grade, branch,
              mos_rating_afsc, enlistment_date, separation_date, total_service_years,
              deployments:[{location,start_date,end_date,combat_zone}], awards_decorations:[...],
              duty_stations:[{name,start_date,end_date}], discharge_type, character_of_service,
              service_connected_events:[{description,date,location}]}. Use null for absent fields.
            - events[] / events_not_found: each distinct dated medical encounter
              {event_date, event_type (primary_care|emergency|mental_health|lab_panel|
              prescription_fill|imaging|surgery|specialty_consult|intake_exam|separation_exam|
              administrative), provider, facility, summary, medications_mentioned:[...],
              diagnoses_mentioned:[...]}
            - atoms[] / atoms_not_found: every atomic fact as {type, value (detailed),
              source, confidence (0.0-1.0), date (YYYY-MM-DD or null)}. Atom types: diagnosis,
              medication, symptom, functional_limitation, event, exposure, test_result, treatment,
              statement, service_record, prescription, lab_result, vital_sign, imaging_result,
              mental_health_score, provider. Be EXHAUSTIVE. Skip normal lab values; include only
              abnormal or claim-relevant results.

            Return ONLY the DocFacts JSON object.
            """;

    // -------------------------------------------------------------------------
    // Request building
    // -------------------------------------------------------------------------

    /**
     * Build the single schema'd {@code extraction_doc} request for one evidence item.
     * Carries multimodal inline data for inlineable PDFs/images, otherwise the text
     * path. {@code userId} is the claim owner (cost/cap attribution).
     */
    public LlmJobRequest buildRequest(EvidenceItem ev, Long claimId, Long userId) {
        String filename = ev.getFilename() != null ? ev.getFilename() : "unknown";
        String batchGroupKey = "extraction_doc_" + claimId;

        InlinePlan plan = planInlineData(ev);

        LlmJobRequest.Builder b = LlmJobRequest.builder()
                .purpose("extraction_doc")
                .systemPrompt(SYSTEM_PROMPT)
                .maxTokens(65536)
                .thinkingBudget(thinkingBudget)
                .responseSchema(docFactsSchema())
                .claimId(claimId)
                .userId(userId)
                .evidenceId(ev.getId())
                .batchGroupKey(batchGroupKey);

        if (plan.inlineData != null) {
            b.userMessage("Extract a DocFacts object from this document (" + filename + ").");
            b.inlineData(plan.inlineData);
        } else {
            // Text path: legacy extraction text (decoded / raw_content), byte-identical
            // to what the per-pass extractors fed the model before Mission B.
            b.userMessage("Extract a DocFacts object from this document (" + filename + "):\n\n"
                    + plan.text);
        }
        return b.build();
    }

    // -------------------------------------------------------------------------
    // Content-addressed extract key (Mission 5a — incremental extraction)
    // -------------------------------------------------------------------------

    /**
     * Compute the deterministic {@code extract_key} for one document: a SHA-256
     * hex digest over four discriminators joined with a NUL (U+0000) delimiter
     * (a byte that never appears in any component, so the components can't run
     * together ambiguously):
     *
     * <ol>
     *   <li><b>Content discriminator.</b> For a file-backed row this is its
     *       {@code file_hash} — already the SHA-256 of the uploaded bytes, so a
     *       re-uploaded identical file matches and a changed file does not. For
     *       text / quick-add evidence (no {@code file_hash}), it is the SHA-256
     *       of the exact extraction text the model would receive
     *       ({@link DocumentStorageService#loadExtractionText}), so edited typed
     *       evidence re-extracts. The text path is taken ONLY when there is no
     *       file_hash, so it never triggers a second GCS download for file rows.</li>
     *   <li><b>{@link #PROMPT_VERSION}</b> — bump ⇒ every doc's key shifts ⇒
     *       global re-extract.</li>
     *   <li><b>{@link #SCHEMA_VERSION}</b> — same global-reextract lever for the
     *       output schema.</li>
     *   <li><b>Routed extraction model id</b> — the model that would actually run
     *       {@code extraction_doc} (config route, else the wired default). A model
     *       swap re-extracts so atoms reflect the new model.</li>
     * </ol>
     *
     * <p>The key is computed BEFORE submitting and compared to the stored key to
     * decide whether to skip; it is written to the row only after a successful
     * parse+persist (a failed doc keeps a stale/null key and re-extracts next
     * round). Returns null only if no content discriminator can be derived at all
     * (an empty row) — a null computed key is treated by the caller as
     * "always re-extract", never as "skip".
     */
    public String computeExtractKey(EvidenceItem ev) {
        String content = contentDiscriminator(ev);
        if (content == null || content.isBlank()) {
            return null;
        }
        String model = routedExtractionModelId();
        String material = String.join("NUL",
                content,
                "prompt:" + PROMPT_VERSION,
                "schema:" + SCHEMA_VERSION,
                "model:" + (model == null ? "" : model));
        return sha256Hex(material);
    }

    /**
     * The content half of the key. Prefers the stored {@code file_hash} (SHA-256
     * of the uploaded bytes — present for every GCS-backed/file upload) so file
     * rows never re-download from GCS just to key them. Falls back to hashing the
     * extraction text for text/quick-add rows that carry no file_hash.
     */
    private String contentDiscriminator(EvidenceItem ev) {
        if (ev.getFileHash() != null && !ev.getFileHash().isBlank()) {
            return "file:" + ev.getFileHash();
        }
        String text = documentStorageService.loadExtractionText(ev);
        if (text == null || text.isEmpty()) {
            return null;
        }
        return "text:" + sha256Hex(text);
    }

    /**
     * The model id that would actually route for {@code extraction_doc}: the
     * configured {@code va-claim.llm.purposes.extraction_doc.model} if present,
     * else the wired fallback (which mirrors the router's vertex-gemini default).
     * Kept in lockstep with {@link com.afterduty.service.llm.LlmProviderRouter}
     * so the key reflects the model the run will use.
     */
    private String routedExtractionModelId() {
        if (routingProperties != null && routingProperties.getPurposes() != null) {
            LlmRoutingProperties.PurposeRoute route =
                    routingProperties.getPurposes().get("extraction_doc");
            if (route != null && route.getModel() != null && !route.getModel().isBlank()) {
                return route.getModel().trim();
            }
        }
        return extractionDocModelFallback;
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // SHA-256 is always available on a JVM; treat an impossible failure as
            // "no key" so the caller re-extracts rather than wrongly skipping.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Plain-language note recorded on success when a big doc fell back from
     * multimodal to the text path. Recomputed from CHEAP METADATA only — the
     * inlineable MIME type plus {@code fileSize} vs the inline ceiling — so the
     * parse path never triggers a second full GCS download + re-base64 just to
     * recover the fallback decision. Returns null when no fallback applies (text
     * evidence, or an inlineable doc that fit under the ceiling).
     */
    public String inlineFallbackMessage(EvidenceItem ev) {
        if (inlineMimeType(ev) == null) {
            // Text / quick-add evidence never inlines, so there is no fallback note.
            return null;
        }
        Long size = ev.getFileSize();
        if (size == null) {
            // Size unknown from metadata: can't assert a fallback happened without a
            // download, and we deliberately avoid one here — treat as no note.
            return null;
        }
        long maxBytes = (long) maxInlineMb * 1024L * 1024L;
        if (size > maxBytes) {
            return "This document was too large to analyze as an image/PDF, so we analyzed "
                    + "its extracted text instead. Some scanned content may not have been captured.";
        }
        return null;
    }

    // Build-time inline plan. The multimodal→text fallback NOTE is computed
    // separately and cheaply by inlineFallbackMessage (metadata only), so it is
    // intentionally not carried here — this record exists only to feed the request.
    private record InlinePlan(List<Map<String, Object>> inlineData, String text) {}

    private InlinePlan planInlineData(EvidenceItem ev) {
        String mime = inlineMimeType(ev);
        if (mime != null) {
            byte[] bytes = documentStorageService.loadFileBytes(ev);
            if (bytes != null && bytes.length > 0) {
                long maxBytes = (long) maxInlineMb * 1024L * 1024L;
                if (bytes.length <= maxBytes) {
                    String b64 = Base64.getEncoder().encodeToString(bytes);
                    List<Map<String, Object>> parts = new ArrayList<>();
                    parts.add(Map.of("mimeType", mime, "data", b64));
                    return new InlinePlan(parts, null);
                }
                // Too large to inline — fall back to the extracted text. The honest
                // fallback note is recorded on success via inlineFallbackMessage.
                return new InlinePlan(null, documentStorageService.loadExtractionText(ev));
            }
        }
        // Text evidence (quick-add / typed / legacy plain text) — text path, no note.
        return new InlinePlan(null, documentStorageService.loadExtractionText(ev));
    }

    /**
     * The MIME type to inline for this evidence, or null when it should go through
     * the text path. Driven by the upload's declared media type / FILE_TYPE header /
     * filename — Gemini multimodal supports PDF and common image types.
     */
    private String inlineMimeType(EvidenceItem ev) {
        String media = ev.getMediaType();
        if (media == null || media.isBlank()) {
            // Legacy rows carry the type inside the base64 envelope's FILE_TYPE header.
            String raw = ev.getRawContent();
            if (raw != null && raw.startsWith("CONTENT_ENCODING: base64")) {
                media = documentStorageService.parseFileType(raw);
            }
        }
        if (media == null) media = "";
        media = media.toLowerCase();
        String filename = ev.getFilename() != null ? ev.getFilename().toLowerCase() : "";

        if (media.contains("pdf") || filename.endsWith(".pdf")) return "application/pdf";
        if (media.contains("png") || filename.endsWith(".png")) return "image/png";
        if (media.contains("jpeg") || media.contains("jpg")
                || filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return "image/jpeg";
        if (media.contains("webp") || filename.endsWith(".webp")) return "image/webp";
        if (media.contains("heic") || filename.endsWith(".heic")) return "image/heic";
        return null;
    }

    // -------------------------------------------------------------------------
    // Result parsing & persistence delegation
    // -------------------------------------------------------------------------

    /**
     * Outcome of parsing one DocFacts result.
     *
     * <p>{@code unreadable} ⇒ the document could not be read; the state machine sets
     * evidence {@code processing_status=error} with {@code reason} as the
     * processing_message (never silent-empty). Otherwise {@code atomsByProvenance}
     * carries each section's atoms keyed by the legacy {@code createdBy} tag, ready
     * to persist exactly as the per-pass stages did; MedicalEvent rows for the
     * events[] section have already been persisted as a side effect of parsing.
     */
    public static final class DocFactsOutcome {
        public final boolean unreadable;
        public final String reason;
        public final LinkedHashMap<String, List<AtomDto>> atomsByProvenance;
        public final int eventCount;

        DocFactsOutcome(boolean unreadable, String reason,
                        LinkedHashMap<String, List<AtomDto>> atomsByProvenance, int eventCount) {
            this.unreadable = unreadable;
            this.reason = reason;
            this.atomsByProvenance = atomsByProvenance;
            this.eventCount = eventCount;
        }
    }

    /**
     * Parse a DocFacts result and persist its events[] section as MedicalEvent rows
     * (via the existing segmentation parser, so the medical_events table and thus
     * CrossEventAggregator behave identically to the legacy SEGMENTS stage).
     *
     * <p>Each fact section is delegated to its legacy per-pass parser by
     * re-serializing the sub-section into the exact JSON shape that parser already
     * consumes — guaranteeing the persisted atom type/value/source/confidence/date
     * are byte-identical to multi-pass output. Atoms are returned grouped by
     * provenance for the state machine to persist with the right {@code createdBy}.
     *
     * <p>A malformed/unparseable DocFacts JSON (the LLM job itself still SUCCEEDED)
     * throws {@link DocFactsParseException} so the state machine fails the stage
     * rather than silently persisting nothing — this kills the extraction half of
     * the silent-failure class. Legitimate abstention (readable doc, empty section)
     * is simply an empty list.
     */
    public DocFactsOutcome parseDocFacts(LlmJobResult result, EvidenceItem evidence) {
        String text = result.getText();
        String filename = evidence.getFilename() != null ? evidence.getFilename() : "unknown";
        if (text == null || text.isBlank()) {
            throw new DocFactsParseException("empty model response");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(stripFences(text));
        } catch (JsonProcessingException e) {
            // PHI hygiene: the Jackson message can echo document-derived JSON
            // fragments. Surface ONLY the exception class + parser line/column; the
            // raw parser detail is logged at debug, never at error / on the claim.
            log.debug("[single-pass] DocFacts JSON parse detail for evidence {}: {}",
                    evidence.getId(), e.getOriginalMessage());
            throw new DocFactsParseException("DocFacts JSON parse failed", e);
        } catch (Exception e) {
            throw new DocFactsParseException("DocFacts JSON parse failed ("
                    + e.getClass().getSimpleName() + ")");
        }
        if (root == null || !root.isObject()) {
            throw new DocFactsParseException("DocFacts response was not a JSON object");
        }

        // Abstention: whole-document unreadable. Surface as error + plain message.
        if (root.path("unreadable_or_unsupported").asBoolean(false)) {
            String reason = root.path("reason").asText(null);
            if (reason == null || reason.isBlank()) {
                reason = "We could not read this document. It may be blank, corrupted, "
                        + "password-protected, or in a format we don't yet support.";
            }
            return new DocFactsOutcome(true, reason, new LinkedHashMap<>(), 0);
        }

        LinkedHashMap<String, List<AtomDto>> groups = new LinkedHashMap<>();
        try {
            groups.put(CREATED_BY_DIAGNOSIS, diagnosisExtractorService.parseResponse(
                    wrapArray(root.path("diagnoses")), filename));
            groups.put(CREATED_BY_MEDICATION, medicationExtractorService.parseResponse(
                    wrapArray(root.path("medications")), filename));
            groups.put(CREATED_BY_SERVICE_RECORD, serviceRecordExtractorService.parseResponse(
                    wrapObject(root.path("service_records")), filename));
            groups.put(CREATED_BY_ATOM, geminiExtractionService.parseResponse(
                    wrapArray(root.path("atoms")), filename));
        } catch (Exception e) {
            // PHI hygiene: a section parser's message can carry document-derived
            // text. Keep only the exception class out; log the detail at debug.
            log.debug("[single-pass] DocFacts section parse detail for evidence {}: {}",
                    evidence.getId(), e.getMessage());
            throw new DocFactsParseException("DocFacts section parse failed ("
                    + e.getClass().getSimpleName() + ")");
        }

        // Silent-empty guard: a structurally valid DocFacts that nonetheless
        // produced ZERO atoms across every section, persisted no events, asserted
        // NONE of the *_not_found abstention flags, and was not flagged unreadable
        // is the exact silent-empty class this increment kills — a "successful"
        // extraction that quietly captured nothing. Detect it BEFORE persisting any
        // events and surface it as a soft error (processing_status=error + a plain
        // message), never a processed-with-zero-output row. Legitimate abstention
        // (a readable doc that truthfully has no meds/diagnoses) sets the relevant
        // *_not_found flags, so it is NOT caught here.
        int totalAtoms = 0;
        for (List<AtomDto> atoms : groups.values()) {
            totalAtoms += atoms.size();
        }
        boolean anyEvents = root.path("events").isArray() && !root.path("events").isEmpty();
        boolean anyAbstentionAsserted =
                root.path("diagnoses_not_found").asBoolean(false)
                        || root.path("medications_not_found").asBoolean(false)
                        || root.path("service_record_not_found").asBoolean(false)
                        || root.path("events_not_found").asBoolean(false)
                        || root.path("atoms_not_found").asBoolean(false);
        if (totalAtoms == 0 && !anyEvents && !anyAbstentionAsserted) {
            String reason = "We couldn't read anything usable from this document. "
                    + "It may be blank, very low quality, or in a format we can't fully process. "
                    + "Try re-uploading a clearer copy.";
            log.info("[single-pass] silent-empty guard tripped for evidence {} — no atoms, "
                    + "no events, no abstention flags", evidence.getId());
            return new DocFactsOutcome(true, reason, new LinkedHashMap<>(), 0);
        }

        // Events: persist MedicalEvent rows through the segmentation parser so the
        // medical_events table is populated exactly as the legacy SEGMENTS stage did.
        int eventCount = 0;
        JsonNode eventsNode = root.path("events");
        if (eventsNode.isArray() && !eventsNode.isEmpty()) {
            List<Map<String, Object>> eventObjects = new ArrayList<>();
            for (JsonNode ev : eventsNode) {
                eventObjects.add(objectMapper.convertValue(ev,
                        new TypeReference<Map<String, Object>>() {}));
            }
            List<MedicalEvent> events =
                    eventSegmentationAgent.parseResponse(synthLlmResult(eventObjects), evidence);
            eventCount = events.size();
            log.info("[single-pass] persisted {} medical events for evidence {}",
                    eventCount, evidence.getId());
        }

        return new DocFactsOutcome(false, null, groups, eventCount);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Wrap a JSON array node as an LlmJobResult whose text is that array (parsers expect array text). */
    private LlmJobResult wrapArray(JsonNode node) {
        String json;
        if (node != null && node.isArray()) {
            json = node.toString();
        } else {
            json = "[]";
        }
        return new LlmJobResult(json, node, 0L, 0L, 0L, "single-pass", "single-pass");
    }

    /** Wrap a JSON object node as an LlmJobResult whose text is that object (service-record parser expects object text). */
    private LlmJobResult wrapObject(JsonNode node) {
        String json;
        if (node != null && node.isObject() && !node.isEmpty()) {
            json = node.toString();
        } else {
            // Sentinel the service-record parser understands as "no data".
            json = "{\"no_service_data\": true}";
        }
        return new LlmJobResult(json, node, 0L, 0L, 0L, "single-pass", "single-pass");
    }

    private LlmJobResult synthLlmResult(List<Map<String, Object>> eventObjects) {
        try {
            String json = objectMapper.writeValueAsString(eventObjects);
            return new LlmJobResult(json, objectMapper.readTree(json), 0L, 0L, 0L,
                    "single-pass", "single-pass");
        } catch (Exception e) {
            return new LlmJobResult("[]", objectMapper.createArrayNode(), 0L, 0L, 0L,
                    "single-pass", "single-pass");
        }
    }

    private String stripFences(String text) {
        String cleaned = text.strip();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.strip();
    }

    /**
     * Thrown when a DocFacts result cannot be parsed (the stage fails rather than
     * silently persisting nothing).
     *
     * <p><b>PHI hygiene.</b> The message is always parser-derived metadata only —
     * never raw document text. The {@link JsonProcessingException} overload appends
     * just the failing location (line/column) and the exception class; the raw
     * Jackson message (which can echo document JSON fragments) is dropped here and
     * logged at debug by the caller, never carried into {@code claim.analysisMessage}
     * or an error-level log.
     */
    public static final class DocFactsParseException extends RuntimeException {
        public DocFactsParseException(String message) { super(message); }

        DocFactsParseException(String message, JsonProcessingException cause) {
            super(message + " (" + cause.getClass().getSimpleName() + locationSuffix(cause) + ")");
        }

        private static String locationSuffix(JsonProcessingException cause) {
            JsonLocation loc = cause.getLocation();
            if (loc == null) {
                return "";
            }
            return " at line " + loc.getLineNr() + ", column " + loc.getColumnNr();
        }
    }

    // -------------------------------------------------------------------------
    // DocFacts response schema (Gemini responseSchema / OpenAPI subset)
    // -------------------------------------------------------------------------

    private Map<String, Object> docFactsSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();

        props.put("doc_type", strProp());
        props.put("doc_date", strProp());
        props.put("unreadable_or_unsupported", Map.of("type", "boolean"));
        props.put("reason", strProp());

        props.put("diagnoses_not_found", Map.of("type", "boolean"));
        props.put("diagnoses", arrayOf(objProps(
                "diagnosis_name", "icd10_code", "date_diagnosed", "diagnosing_provider",
                "severity", "status", "related_conditions", "functional_limitations")));

        props.put("medications_not_found", Map.of("type", "boolean"));
        props.put("medications", arrayOf(objProps(
                "drug_name", "dosage", "frequency", "route", "prescriber",
                "start_date", "end_date", "purpose", "changes")));

        props.put("service_record_not_found", Map.of("type", "boolean"));
        props.put("service_records", serviceRecordSchema());

        props.put("events_not_found", Map.of("type", "boolean"));
        props.put("events", arrayOf(eventProps()));

        props.put("atoms_not_found", Map.of("type", "boolean"));
        props.put("atoms", arrayOf(atomProps()));

        schema.put("properties", props);
        // EVERY top-level key is required. Without this, Vertex constrained
        // decoding accepts ANY property subset as schema-valid, and live
        // gemini-3.1-pro-preview non-deterministically emits a minimal object
        // (doc_type + one flag, 0 atoms) with finishReason=STOP — the
        // eval-discovered empty-extraction coin flip (see
        // docs/architecture/extraction-empty-triage.md; probe-verified fix).
        // Fields the model has nothing for are still emitted as null/false/[]
        // because every property is nullable or has a natural empty value.
        schema.put("required", new ArrayList<>(props.keySet()));
        return schema;
    }

    private Map<String, Object> strProp() { return Map.of("type", "string", "nullable", true); }

    private Map<String, Object> arrayOf(Map<String, Object> itemSchema) {
        Map<String, Object> arr = new LinkedHashMap<>();
        arr.put("type", "array");
        arr.put("items", itemSchema);
        return arr;
    }

    private Map<String, Object> objProps(String... keys) {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        for (String k : keys) props.put(k, strProp());
        obj.put("properties", props);
        return obj;
    }

    private Map<String, Object> atomProps() {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("type", strProp());
        props.put("value", strProp());
        props.put("source", strProp());
        props.put("confidence", Map.of("type", "number", "nullable", true));
        props.put("date", strProp());
        obj.put("properties", props);
        return obj;
    }

    private Map<String, Object> eventProps() {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("event_date", strProp());
        props.put("event_type", strProp());
        props.put("provider", strProp());
        props.put("facility", strProp());
        props.put("summary", strProp());
        props.put("medications_mentioned", arrayOf(Map.of("type", "string")));
        props.put("diagnoses_mentioned", arrayOf(Map.of("type", "string")));
        obj.put("properties", props);
        return obj;
    }

    private Map<String, Object> serviceRecordSchema() {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("type", "object");
        obj.put("nullable", true);
        Map<String, Object> props = new LinkedHashMap<>();
        for (String k : new String[]{"rank", "pay_grade", "branch", "mos_rating_afsc",
                "enlistment_date", "separation_date", "total_service_years",
                "discharge_type", "character_of_service"}) {
            props.put(k, strProp());
        }
        props.put("deployments", arrayOf(objProps("location", "start_date", "end_date", "combat_zone")));
        props.put("awards_decorations", arrayOf(Map.of("type", "string")));
        props.put("duty_stations", arrayOf(objProps("name", "start_date", "end_date")));
        props.put("service_connected_events", arrayOf(objProps("description", "date", "location")));
        obj.put("properties", props);
        return obj;
    }
}
