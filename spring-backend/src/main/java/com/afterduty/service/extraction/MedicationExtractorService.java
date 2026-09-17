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
 * Specialized extractor for medication information.
 * Exposes buildRequest/parseResponse for the async LLM pipeline.
 */
@Service
public class MedicationExtractorService {

    private static final Logger log = LoggerFactory.getLogger(MedicationExtractorService.class);

    private final LlmJobService llmJobService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String MEDICATION_SYSTEM_PROMPT = """
            Extract ONLY medication information from this document. For each medication found:
            - drug_name: exact name (e.g., prednisone, sertraline, metformin)
            - dosage: amount and unit (e.g., 50mg, 10mg/mL)
            - frequency: how often taken (e.g., daily, twice daily, as needed, 3 times per year)
            - route: how administered (oral, topical, injection, inhaler)
            - prescriber: who prescribed it
            - start_date: when started (YYYY-MM-DD if known, null otherwise)
            - end_date: when stopped (YYYY-MM-DD if known, 'ongoing' if current, null if unknown)
            - purpose: what condition it treats
            - changes: any dosage changes, tapers, or discontinuation notes

            Return a JSON array of objects with the fields above.
            If no medications are found, return an empty array [].
            Return ONLY the JSON array, no other text.
            """;

    public MedicationExtractorService(LlmJobService llmJobService) {
        this.llmJobService = llmJobService;
    }

    /**
     * Convenience: submit a medication-extraction job via LlmJobService.
     */
    public UUID submit(String documentText, String filename, Long claimId, Long evidenceId) {
        return llmJobService.submit(buildRequest(documentText, filename, claimId, evidenceId));
    }

    /**
     * Build a provider-agnostic LlmJobRequest for medication extraction.
     */
    public LlmJobRequest buildRequest(String documentText, String filename, Long claimId, Long evidenceId) {
        String userMessage = "Extract all medication information from this document (" + filename + "):\n\n" + documentText;
        return LlmJobRequest.builder()
                .purpose("extraction_medication")
                .systemPrompt(MEDICATION_SYSTEM_PROMPT)
                .userMessage(userMessage)
                .maxTokens(16384)
                .claimId(claimId)
                .userId(null)
                .evidenceId(evidenceId)
                .batchGroupKey("extraction_medication_" + claimId)
                .build();
    }

    /**
     * Parse the LLM response into a list of AtomDtos.
     */
    public List<AtomDto> parseResponse(LlmJobResult result, String filename) {
        String text = result.getText();
        if (text == null || text.isBlank()) return List.of();
        return parseMedicationResponse(text, filename);
    }

    private List<AtomDto> parseMedicationResponse(String responseText, String filename) {
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

            List<Map<String, Object>> rawMedications = objectMapper.readValue(
                    cleaned, new TypeReference<List<Map<String, Object>>>() {});

            List<AtomDto> atoms = new ArrayList<>();
            for (Map<String, Object> med : rawMedications) {
                String drugName = strOrNull(med, "drug_name");
                if (drugName == null || drugName.isBlank()) continue;

                StringBuilder valueBuilder = new StringBuilder();
                valueBuilder.append("Medication: ").append(drugName);

                String dosage = strOrNull(med, "dosage");
                if (dosage != null) valueBuilder.append(" | Dosage: ").append(dosage);

                String frequency = strOrNull(med, "frequency");
                if (frequency != null) valueBuilder.append(" | Frequency: ").append(frequency);

                String route = strOrNull(med, "route");
                if (route != null) valueBuilder.append(" | Route: ").append(route);

                String prescriber = strOrNull(med, "prescriber");
                if (prescriber != null) valueBuilder.append(" | Prescriber: ").append(prescriber);

                String purpose = strOrNull(med, "purpose");
                if (purpose != null) valueBuilder.append(" | Purpose: ").append(purpose);

                String changes = strOrNull(med, "changes");
                if (changes != null) valueBuilder.append(" | Changes: ").append(changes);

                String startDate = strOrNull(med, "start_date");
                String endDate = strOrNull(med, "end_date");

                // Use start_date as the atom date if available
                String atomDate = startDate;

                atoms.add(AtomDto.builder()
                        .type("medication_detail")
                        .value(valueBuilder.toString())
                        .source(filename)
                        .confidence(0.92)
                        .date(atomDate)
                        .build());
            }

            log.info("Parsed {} medication atoms from specialized extraction", atoms.size());
            return atoms;

        } catch (Exception e) {
            log.error("Failed to parse medication extraction response: {}", e.getMessage());
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
