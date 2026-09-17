package com.afterduty.service.extraction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.EvidenceItem;
import com.afterduty.model.MedicalEvent;
import com.afterduty.repository.MedicalEventRepository;
import com.afterduty.service.llm.LlmJobRequest;
import com.afterduty.service.llm.LlmJobResult;
import com.afterduty.service.llm.LlmJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.UUID;

/**
 * Segments a medical document into individual medical events.
 * Uses a fast, low-budget Gemini call to identify event boundaries and basic metadata.
 */
@Service
public class EventSegmentationAgent {

    private static final Logger log = LoggerFactory.getLogger(EventSegmentationAgent.class);

    private final MedicalEventRepository medicalEventRepository;
    private final LlmJobService llmJobService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int SEGMENTATION_THINKING_BUDGET = 2048;

    private static final String SEGMENTATION_SYSTEM_PROMPT = """
            You are a medical document segmentation specialist. Scan this document and identify every distinct medical event or encounter.

            A medical event is: a doctor visit, lab panel, prescription fill, imaging study, surgery, mental health session, intake exam, separation physical, emergency visit, or any distinct dated medical interaction.

            For EACH event found, return:
            {
              "event_date": "YYYY-MM-DD or approximate",
              "event_type": "primary_care|emergency|mental_health|lab_panel|prescription_fill|imaging|surgery|specialty_consult|intake_exam|separation_exam|administrative",
              "provider": "provider name if visible",
              "facility": "facility name if visible",
              "summary": "1-2 sentence description of what happened",
              "start_marker": "the first distinctive text of this event (20-30 chars for matching)",
              "end_marker": "the last distinctive text of this event (20-30 chars for matching)",
              "medications_mentioned": ["drug names mentioned in this event"],
              "diagnoses_mentioned": ["conditions mentioned in this event"]
            }

            Return a JSON array ordered by date. Include ALL events — do not skip any.
            Be FAST — this is a segmentation task, not a deep extraction. Just identify boundaries and basic metadata.
            """;

    public EventSegmentationAgent(MedicalEventRepository medicalEventRepository, LlmJobService llmJobService) {
        this.medicalEventRepository = medicalEventRepository;
        this.llmJobService = llmJobService;
    }

    /**
     * Segment a PDF document into medical events.
     * @deprecated Use the async pipeline via ExtractionStateMachine instead.
     */
    @Deprecated
    public List<MedicalEvent> segmentDocument(byte[] pdfBytes, String filename, Long claimId, Long evidenceId) {
        throw new UnsupportedOperationException(
                "Synchronous segmentation removed. Use the async extraction pipeline (ExtractionStateMachine).");
    }

    /**
     * Segment a text document into medical events.
     * @deprecated Use the async pipeline via ExtractionStateMachine instead.
     */
    @Deprecated
    public List<MedicalEvent> segmentText(String text, String filename, Long claimId, Long evidenceId) {
        throw new UnsupportedOperationException(
                "Synchronous segmentation removed. Use the async extraction pipeline (ExtractionStateMachine).");
    }

    /**
     * Convenience: submit an event-segmentation job via LlmJobService.
     */
    public UUID submit(String text, String filename, Long claimId, Long evidenceId) {
        return llmJobService.submit(buildRequest(text, filename, claimId, evidenceId));
    }

    /**
     * Build a provider-agnostic LlmJobRequest for event segmentation from text content.
     */
    public LlmJobRequest buildRequest(String text, String filename, Long claimId, Long evidenceId) {
        String userMessage = "Segment this medical document into individual events (" + filename + "):\n\n" + text;
        return LlmJobRequest.builder()
                .purpose("extraction_event_segment")
                .systemPrompt(SEGMENTATION_SYSTEM_PROMPT)
                .userMessage(userMessage)
                .maxTokens(32768)
                .thinkingBudget(SEGMENTATION_THINKING_BUDGET)
                .claimId(claimId)
                .userId(null)
                .evidenceId(evidenceId)
                .batchGroupKey("extraction_event_segment_" + claimId)
                .build();
    }

    /**
     * Parse the LLM response into medical events and persist them as a side effect.
     */
    public List<MedicalEvent> parseResponse(LlmJobResult result, EvidenceItem evidence) {
        String text = result.getText();
        if (text == null || text.isBlank()) return List.of();
        List<MedicalEvent> events = parseSegmentationResponse(text, evidence.getClaimId(),
                evidence.getId(), evidence.getFilename() != null ? evidence.getFilename() : "unknown");
        if (!events.isEmpty()) {
            medicalEventRepository.saveAll(events);
            log.info("Saved {} medical events from {} for claim={}", events.size(),
                    evidence.getFilename(), evidence.getClaimId());
        }
        return events;
    }

    private List<MedicalEvent> parseSegmentationResponse(String responseText, Long claimId,
                                                          Long evidenceId, String filename) {
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

            List<Map<String, Object>> rawEvents = objectMapper.readValue(
                    cleaned, new TypeReference<List<Map<String, Object>>>() {});

            List<MedicalEvent> events = new ArrayList<>();
            for (Map<String, Object> raw : rawEvents) {
                String eventDate = raw.get("event_date") != null ? raw.get("event_date").toString() : null;
                if ("null".equals(eventDate)) eventDate = null;

                String eventType = (String) raw.getOrDefault("event_type", "unknown");
                String provider = raw.get("provider") != null ? raw.get("provider").toString() : null;
                String facility = raw.get("facility") != null ? raw.get("facility").toString() : null;
                String summary = (String) raw.getOrDefault("summary", "");

                // Convert lists to JSON strings
                String medicationsMentioned = null;
                Object medsObj = raw.get("medications_mentioned");
                if (medsObj != null) {
                    medicationsMentioned = objectMapper.writeValueAsString(medsObj);
                }

                String diagnosesMentioned = null;
                Object diagObj = raw.get("diagnoses_mentioned");
                if (diagObj != null) {
                    diagnosesMentioned = objectMapper.writeValueAsString(diagObj);
                }

                // Build raw text from start/end markers if possible (text matching deferred)
                String startMarker = raw.get("start_marker") != null ? raw.get("start_marker").toString() : null;
                String endMarker = raw.get("end_marker") != null ? raw.get("end_marker").toString() : null;

                MedicalEvent event = MedicalEvent.builder()
                        .claimId(claimId)
                        .evidenceId(evidenceId)
                        .eventDate(eventDate)
                        .eventType(eventType)
                        .provider(provider)
                        .facility(facility)
                        .summary(summary)
                        .rawText(startMarker != null && endMarker != null
                                ? "[start: " + startMarker + "] ... [end: " + endMarker + "]"
                                : null)
                        .medicationsMentioned(medicationsMentioned)
                        .diagnosesMentioned(diagnosesMentioned)
                        .build();

                events.add(event);
            }

            log.info("Parsed {} medical events from segmentation response", events.size());
            return events;

        } catch (Exception e) {
            log.error("Failed to parse segmentation response: {}", e.getMessage());
            log.debug("Raw response: {}", responseText);
            return List.of();
        }
    }
}
