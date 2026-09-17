package com.afterduty.service.extraction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.dto.AtomDto;
import com.afterduty.model.MedicalEvent;
import com.afterduty.repository.MedicalEventRepository;
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
 * Extracts atoms from a single medical event.
 * Uses document-type-specific prompts adapted for single-event context.
 */
@Service
public class EventExtractionAgent {

    private static final Logger log = LoggerFactory.getLogger(EventExtractionAgent.class);

    @Value("${va-claim.gemini.project-id:}")
    private String projectId;

    @Value("${va-claim.gemini.location:global}")
    private String location;

    @Value("${va-claim.gemini.model:gemini-3.1-pro-preview}")
    private String modelName;

    @Value("${va-claim.gemini.thinking-budget:8192}")
    private int thinkingBudget;

    private final MedicalEventRepository medicalEventRepository;
    private final LlmJobService llmJobService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Map<String, String> EVENT_TYPE_INSTRUCTIONS = Map.ofEntries(
            Map.entry("primary_care", """
                    Focus on: diagnoses discussed, medications prescribed/adjusted, vitals, lab orders, \
                    functional limitations noted, referrals made, follow-up instructions."""),
            Map.entry("emergency", """
                    Focus on: chief complaint, triage assessment, diagnoses, treatments administered, \
                    medications given, discharge instructions, follow-up referrals."""),
            Map.entry("mental_health", """
                    Focus on: assessment scores (PHQ-9, PCL-5, GAF, Columbia), diagnoses, medications \
                    adjusted, therapy modality, risk assessment, functional impact statements, \
                    sleep/concentration/social functioning observations."""),
            Map.entry("lab_panel", """
                    Focus on: ONLY abnormal values. For each: test name, value with units, reference range, \
                    clinical significance. Skip all normal results."""),
            Map.entry("prescription_fill", """
                    Focus on: drug name, exact dosage, frequency, route, prescriber, fill date, \
                    quantity dispensed, refills remaining, purpose/condition treated."""),
            Map.entry("imaging", """
                    Focus on: modality (X-ray/MRI/CT/ultrasound), body part, findings (normal and abnormal), \
                    impressions, comparison to prior studies, interpreting radiologist."""),
            Map.entry("surgery", """
                    Focus on: procedure name, indication, surgical findings, complications, \
                    surgeon, anesthesia type, post-operative instructions, pathology results."""),
            Map.entry("specialty_consult", """
                    Focus on: reason for referral, specialist findings, diagnoses, recommended treatment plan, \
                    nexus opinions if present, functional assessments."""),
            Map.entry("intake_exam", """
                    Focus on: baseline measurements, all conditions noted, medications at intake, \
                    functional status, mental health screening results, deployment history."""),
            Map.entry("separation_exam", """
                    Focus on: all conditions noted at separation, comparison to intake, \
                    new conditions since enlistment, functional limitations, disability claims filed."""),
            Map.entry("administrative", """
                    Focus on: any clinically relevant information, profile changes, duty limitations, \
                    deployment eligibility changes, medical board findings.""")
    );

    public EventExtractionAgent(MedicalEventRepository medicalEventRepository, LlmJobService llmJobService) {
        this.medicalEventRepository = medicalEventRepository;
        this.llmJobService = llmJobService;
    }

    /**
     * Extract atoms from a single medical event (synchronous, debug use only).
     * @deprecated Use the async pipeline via ExtractionStateMachine instead.
     */
    @Deprecated
    public List<AtomDto> extractFromEvent(MedicalEvent event, Long claimId) {
        throw new UnsupportedOperationException(
                "Synchronous event extraction removed. Use the async extraction pipeline (ExtractionStateMachine).");
    }

    /**
     * Convenience: submit an event-extraction job via LlmJobService.
     */
    public UUID submit(MedicalEvent event, Long claimId) {
        return llmJobService.submit(buildRequest(event, claimId));
    }

    /**
     * Build a provider-agnostic LlmJobRequest for event-level atom extraction.
     */
    public LlmJobRequest buildRequest(MedicalEvent event, Long claimId) {
        String systemPrompt = buildEventExtractionPrompt(event);
        String userMessage = buildUserContent(event);
        return LlmJobRequest.builder()
                .purpose("extraction_event")
                .systemPrompt(systemPrompt)
                .userMessage(userMessage)
                .maxTokens(32768)
                .thinkingBudget(thinkingBudget)
                .claimId(claimId)
                .userId(null)
                .evidenceId(event.getEvidenceId())
                .batchGroupKey("extraction_event_" + claimId)
                .build();
    }

    /**
     * Parse the LLM response into atoms AND mark the event as extracted (side effect).
     */
    public List<AtomDto> parseResponse(LlmJobResult result, MedicalEvent event) {
        String text = result.getText();
        if (text == null || text.isBlank()) {
            event.setExtracted(true);
            event.setAtomCount(0);
            medicalEventRepository.save(event);
            return List.of();
        }
        List<AtomDto> atoms = parseAtomResponse(text, event);
        event.setExtracted(true);
        event.setAtomCount(atoms.size());
        medicalEventRepository.save(event);
        return atoms;
    }

    private String buildEventExtractionPrompt(MedicalEvent event) {
        String typeInstructions = EVENT_TYPE_INSTRUCTIONS.getOrDefault(
                event.getEventType() != null ? event.getEventType() : "primary_care",
                "Extract ALL relevant medical facts from this event.");

        return String.format("""
                You are extracting medical evidence from a single medical event for a VA disability claim.

                Event Date: %s
                Event Type: %s
                Provider: %s
                Facility: %s
                Summary: %s

                Extract ALL atoms from this event. Since this is a single encounter, be thorough \
                — every medication, every finding, every measurement.

                %s

                For each atom, assess confidence:
                - 0.95+ = explicitly stated with specifics
                - 0.80-0.94 = clearly stated but missing some specifics
                - 0.60-0.79 = implied or partially documented
                - 0.40-0.59 = inferred from context

                Return a JSON array of atoms. Each atom must have:
                {"type": "<atom_type>", "value": "<detailed description>", "source": "<source>", "confidence": <0-1>, "date": "<YYYY-MM-DD or null>"}

                Atom types: diagnosis, medication, symptom, functional_limitation, event, exposure, \
                test_result, treatment, statement, prescription, lab_result, vital_sign, \
                imaging_result, mental_health_score, provider, service_record.

                Return ONLY the JSON array, no other text.
                """,
                event.getEventDate() != null ? event.getEventDate() : "unknown",
                event.getEventType() != null ? event.getEventType() : "unknown",
                event.getProvider() != null ? event.getProvider() : "unknown",
                event.getFacility() != null ? event.getFacility() : "unknown",
                event.getSummary() != null ? event.getSummary() : "no summary",
                typeInstructions);
    }

    private String buildUserContent(MedicalEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("Extract all atoms from this medical event.\n\n");

        if (event.getRawText() != null && !event.getRawText().isBlank()) {
            sb.append("Event text:\n").append(event.getRawText());
        } else {
            // Fall back to summary + metadata if raw text unavailable
            sb.append("Event summary: ").append(event.getSummary() != null ? event.getSummary() : "").append("\n");
            if (event.getMedicationsMentioned() != null) {
                sb.append("Medications mentioned: ").append(event.getMedicationsMentioned()).append("\n");
            }
            if (event.getDiagnosesMentioned() != null) {
                sb.append("Diagnoses mentioned: ").append(event.getDiagnosesMentioned()).append("\n");
            }
        }

        return sb.toString();
    }

    private List<AtomDto> parseAtomResponse(String responseText, MedicalEvent event) {
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

            String sourcePrefix = "event:" + event.getId() + " (" + event.getEventType() + " " + event.getEventDate() + ")";

            List<AtomDto> atoms = new ArrayList<>();
            for (Map<String, Object> raw : rawAtoms) {
                String type = (String) raw.getOrDefault("type", "unknown");
                String value = (String) raw.getOrDefault("value", "");
                String source = raw.get("source") != null ? sourcePrefix + " - " + raw.get("source") : sourcePrefix;
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

            return atoms;

        } catch (Exception e) {
            log.error("Failed to parse event extraction response for event {}: {}",
                    event.getId(), e.getMessage());
            log.debug("Raw response: {}", responseText);
            return List.of();
        }
    }
}
