package com.afterduty.service.extraction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.dto.AtomDto;
import com.afterduty.service.llm.LlmJobRequest;
import com.afterduty.service.llm.LlmJobResult;
import com.afterduty.service.llm.LlmJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.UUID;

/**
 * Specialized extractor for diagnoses and medical conditions.
 * Exposes buildRequest/parseResponse for the async LLM pipeline.
 */
@Service
public class DiagnosisExtractorService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisExtractorService.class);

    private final LlmJobService llmJobService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String DIAGNOSIS_SYSTEM_PROMPT = """
            Extract ONLY diagnoses and medical conditions from this document. For each diagnosis found:
            - diagnosis_name: full name of the condition
            - icd10_code: ICD-10 code if mentioned (e.g., F32.1, M54.5)
            - date_diagnosed: YYYY-MM-DD if known, null otherwise
            - diagnosing_provider: name and credentials of the provider who made the diagnosis
            - severity: mild, moderate, or severe if stated; null if not
            - status: active, resolved, or chronic
            - related_conditions: any mentioned connections to other conditions
            - functional_limitations: impact on daily life as described in the document

            Return a JSON array of objects with the fields above.
            If no diagnoses are found, return an empty array [].
            Return ONLY the JSON array, no other text.
            """;

    public DiagnosisExtractorService(LlmJobService llmJobService) {
        this.llmJobService = llmJobService;
    }

    /**
     * Convenience: submit a diagnosis-extraction job via LlmJobService.
     */
    public UUID submit(String documentText, String filename, Long claimId, Long evidenceId) {
        return llmJobService.submit(buildRequest(documentText, filename, claimId, evidenceId));
    }

    /**
     * Build a provider-agnostic LlmJobRequest for diagnosis extraction.
     */
    public LlmJobRequest buildRequest(String documentText, String filename, Long claimId, Long evidenceId) {
        String userMessage = "Extract all diagnoses and medical conditions from this document (" + filename + "):\n\n" + documentText;
        return LlmJobRequest.builder()
                .purpose("extraction_diagnosis")
                .systemPrompt(DIAGNOSIS_SYSTEM_PROMPT)
                .userMessage(userMessage)
                .maxTokens(16384)
                .claimId(claimId)
                .userId(null)
                .evidenceId(evidenceId)
                .batchGroupKey("extraction_diagnosis_" + claimId)
                .build();
    }

    /**
     * Parse the LLM response into a list of AtomDtos.
     */
    public List<AtomDto> parseResponse(LlmJobResult result, String filename) {
        String text = result.getText();
        if (text == null || text.isBlank()) return List.of();
        return parseDiagnosisResponse(text, filename);
    }

    private List<AtomDto> parseDiagnosisResponse(String responseText, String filename) {
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

            List<Map<String, Object>> rawDiagnoses = objectMapper.readValue(
                    cleaned, new TypeReference<List<Map<String, Object>>>() {});

            List<AtomDto> atoms = new ArrayList<>();
            for (Map<String, Object> diag : rawDiagnoses) {
                String diagName = strOrNull(diag, "diagnosis_name");

                // Fallback: if the model returned the generic atom format {type, value, source, confidence}
                // instead of the specialized diagnosis format, create an atom directly from "value".
                if (diagName == null || diagName.isBlank()) {
                    String atomValue = strOrNull(diag, "value");
                    if (atomValue != null && !atomValue.isBlank()) {
                        String atomSource = strOrNull(diag, "source");
                        Object confObj = diag.get("confidence");
                        double conf = confObj instanceof Number n ? n.doubleValue() : 0.93;
                        atoms.add(AtomDto.builder()
                                .type("diagnosis_detail")
                                .value(atomValue)
                                .source(atomSource != null ? atomSource : filename)
                                .confidence(conf)
                                .build());
                    }
                    continue;
                }

                StringBuilder valueBuilder = new StringBuilder();
                valueBuilder.append("Diagnosis: ").append(diagName);

                String icd10 = strOrNull(diag, "icd10_code");
                if (icd10 != null) valueBuilder.append(" | ICD-10: ").append(icd10);

                String provider = strOrNull(diag, "diagnosing_provider");
                if (provider != null) valueBuilder.append(" | Provider: ").append(provider);

                String severity = strOrNull(diag, "severity");
                if (severity != null) valueBuilder.append(" | Severity: ").append(severity);

                String status = strOrNull(diag, "status");
                if (status != null) valueBuilder.append(" | Status: ").append(status);

                String related = strOrNull(diag, "related_conditions");
                if (related != null) valueBuilder.append(" | Related: ").append(related);

                String limitations = strOrNull(diag, "functional_limitations");
                if (limitations != null) valueBuilder.append(" | Functional Limitations: ").append(limitations);

                String dateDiagnosed = strOrNull(diag, "date_diagnosed");

                atoms.add(AtomDto.builder()
                        .type("diagnosis_detail")
                        .value(valueBuilder.toString())
                        .source(filename)
                        .confidence(0.93)
                        .date(dateDiagnosed)
                        .build());
            }

            log.info("Parsed {} diagnosis atoms from specialized extraction", atoms.size());
            return atoms;

        } catch (Exception e) {
            log.error("Failed to parse diagnosis extraction response: {}", e.getMessage());
            log.debug("Raw response: {}", responseText);
            return List.of();
        }
    }

    private String strOrNull(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null || "null".equals(val.toString())) return null;
        String s = val.toString().strip();
        return s.isEmpty() ? null : s;
    }
}
