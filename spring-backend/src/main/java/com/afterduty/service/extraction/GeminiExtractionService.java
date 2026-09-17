package com.afterduty.service.extraction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.dto.AtomDto;
import com.afterduty.service.llm.LlmJobRequest;
import com.afterduty.service.llm.LlmJobResult;
import com.afterduty.service.llm.LlmJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.UUID;

/**
 * Generic atom extractor. Exposes buildRequest/parseResponse for the async LLM pipeline.
 * The old synchronous Gemini REST calls have been removed; extraction is now driven
 * asynchronously by ExtractionStateMachine via LlmJobService.
 */
@Service
public class GeminiExtractionService {

    private static final Logger log = LoggerFactory.getLogger(GeminiExtractionService.class);

    @Value("${va-claim.gemini.thinking-budget:8192}")
    private int thinkingBudget;

    private final LlmJobService llmJobService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String EXTRACTION_SYSTEM_PROMPT = """
            You are a VA disability claims evidence extraction AI.
            Analyze this document and extract every atomic fact (atom) relevant to a VA disability claim.

            Atom types: diagnosis, medication, symptom, functional_limitation, event, exposure, date, provider, test_result,
            treatment, statement, service_record, prescription, lab_result, vital_sign, imaging_result,
            mental_health_score.

            CRITICAL EXTRACTION RULES:
            - Be EXHAUSTIVE - extract every individual fact, not summaries
            - Every medication: name, dosage, frequency, prescriber, status (active/discontinued), start/end dates
            - Every lab result: ONLY extract values that are OUTSIDE the reference range (abnormal) or directly relevant to a claimed condition. Skip routine normal lab values. For abnormal results: test name, value with units, reference range, date, why it's significant
            - Every diagnosis: condition name, ICD-10 or SNOMED code if present, date of diagnosis, diagnosing provider
            - Every service record detail: exact dates of service, rank at separation, MOS/rating/NEC, awards/decorations, deployments with locations and dates, duty stations
            - Every mental health score: assessment instrument name, raw score, interpretation, date administered
            - Every symptom: description, frequency (daily/weekly/constant), severity (mild/moderate/severe), functional impact
            - Every functional limitation: what the veteran cannot do or struggles with, impact on work/daily activities, need for assistive devices, social/occupational impairment
            - Every vital sign: type, value with units, date, normal/abnormal
            - Every imaging result: modality (X-ray/MRI/CT), body part, findings, date, interpreting radiologist
            - Every treatment: type (surgery/therapy/procedure), date, provider, outcome
            - Every prescription: drug name, dose, route, frequency, prescriber, fill dates, refills remaining
            - Every exposure: agent (burn pit, Agent Orange, radiation, asbestos, noise), location, duration, dates
            - Every statement: who said it, what they said, context, date
            - Every event: what happened, when, where, who was involved, military context

            For each atom, assess confidence:
            - 0.95+ = explicitly stated with specifics (exact diagnosis, exact date, exact lab value)
            - 0.80-0.94 = clearly stated but missing some specifics
            - 0.60-0.79 = implied or partially documented
            - 0.40-0.59 = inferred from context
            - Below 0.40 = speculative, do not include

            Return a JSON array of atoms. Each atom must have these fields:
            {
              "type": "<atom_type>",
              "value": "<detailed description of the fact>",
              "source": "<document section or page where found>",
              "confidence": <0.0-1.0>,
              "date": "<YYYY-MM-DD if known, null otherwise>"
            }

            SELF-CHECK before returning:
            - Did you extract ALL medications with dose, frequency, and dates?
            - Did you extract functional limitations (how conditions affect daily life)?
            - Did you skip normal lab values?
            - For corticosteroids, did you create a SEPARATE atom for each prescription from each source document?
            If any check fails, re-scan the document and add missing atoms.

            Return ONLY the JSON array, no other text.
            """;

    public GeminiExtractionService(LlmJobService llmJobService) {
        this.llmJobService = llmJobService;
    }

    /**
     * Convenience: submit a generic atom-extraction job via LlmJobService.
     */
    public UUID submit(String documentText, String filename, Long claimId, Long evidenceId) {
        return llmJobService.submit(buildRequest(documentText, filename, claimId, evidenceId));
    }

    /**
     * Build a provider-agnostic LlmJobRequest for generic atom extraction from text.
     */
    public LlmJobRequest buildRequest(String documentText, String filename, Long claimId, Long evidenceId) {
        String systemPrompt = getDocumentTypePrompt(filename);
        String userMessage = "Extract all atoms from this document (" + filename + "):\n\n" + documentText;
        return LlmJobRequest.builder()
                .purpose("extraction_atom")
                .systemPrompt(systemPrompt)
                .userMessage(userMessage)
                .maxTokens(65536)
                .thinkingBudget(thinkingBudget)
                .claimId(claimId)
                .userId(null)
                .evidenceId(evidenceId)
                .batchGroupKey("extraction_atom_" + claimId)
                .build();
    }

    /**
     * Parse the LLM response into a list of AtomDtos.
     */
    public List<AtomDto> parseResponse(LlmJobResult result, String filename) {
        String text = result.getText();
        if (text == null || text.isBlank()) return List.of();
        return parseAtomResponse(text, filename);
    }

    String getDocumentTypePrompt(String filename) {
        if (filename == null) return EXTRACTION_SYSTEM_PROMPT;
        String lower = filename.toLowerCase();

        if (lower.contains("health summary") || lower.contains("blue button")) {
            return DocumentTypePrompts.getPromptForDocumentType("health_summary");
        } else if (lower.contains("imo") || lower.contains("nexus") || lower.contains("letter")) {
            return DocumentTypePrompts.getPromptForDocumentType("imo_letter");
        } else if (lower.contains("dd214") || lower.contains("dd-214")) {
            return DocumentTypePrompts.getPromptForDocumentType("dd214");
        } else if (lower.contains("dbq") || lower.contains("questionnaire")) {
            return DocumentTypePrompts.getPromptForDocumentType("dbq");
        } else if (lower.contains("personal") || lower.contains("statement") || lower.contains("buddy")) {
            return DocumentTypePrompts.getPromptForDocumentType("personal_statement");
        }

        return EXTRACTION_SYSTEM_PROMPT;
    }

    private List<AtomDto> parseAtomResponse(String responseText, String filename) {
        try {
            String cleaned = responseText.strip();
            if (cleaned.startsWith("```json")) {
                cleaned = cleaned.substring(7);
            } else if (cleaned.startsWith("```")) {
                cleaned = cleaned.substring(3);
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3);
            }
            cleaned = cleaned.strip();

            List<Map<String, Object>> rawAtoms = objectMapper.readValue(
                    cleaned, new TypeReference<List<Map<String, Object>>>() {});

            List<AtomDto> atoms = new ArrayList<>();
            for (Map<String, Object> raw : rawAtoms) {
                String type = (String) raw.getOrDefault("type", "unknown");
                String value = (String) raw.getOrDefault("value", "");
                String source = (String) raw.getOrDefault("source", filename);
                Object confObj = raw.get("confidence");
                double confidence = confObj instanceof Number n ? n.doubleValue() : 0.8;
                String date = raw.get("date") != null ? raw.get("date").toString() : null;
                if ("null".equals(date)) date = null;

                if (!value.isEmpty()) {
                    atoms.add(AtomDto.builder()
                            .type(type)
                            .value(value)
                            .source(source)
                            .confidence(confidence)
                            .date(date)
                            .build());
                }
            }

            log.info("Parsed {} atoms from Gemini response", atoms.size());
            return atoms;

        } catch (Exception e) {
            log.error("Failed to parse Gemini response: {}", e.getMessage());
            log.debug("Raw response: {}", responseText);
            return List.of();
        }
    }
}
