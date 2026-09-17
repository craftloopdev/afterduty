package com.afterduty.service.extraction;

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
 * Specialized extractor for military service record information.
 * Exposes buildRequest/parseResponse for the async LLM pipeline.
 */
@Service
public class ServiceRecordExtractorService {

    private static final Logger log = LoggerFactory.getLogger(ServiceRecordExtractorService.class);

    private final LlmJobService llmJobService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SERVICE_RECORD_SYSTEM_PROMPT = """
            Extract ONLY military service information from this document. Return a JSON object with these fields:
            - rank: final or current rank
            - pay_grade: e.g., E-5, O-3
            - branch: Army, Navy, Air Force, Marines, Coast Guard, Space Force
            - mos_rating_afsc: Military Occupational Specialty / rating / AFSC job code
            - enlistment_date: YYYY-MM-DD if known
            - separation_date: YYYY-MM-DD if known
            - total_service_years: number if calculable
            - deployments: [{"location": "", "start_date": "", "end_date": "", "combat_zone": true/false}]
            - awards_decorations: ["award name", ...]
            - duty_stations: [{"name": "", "start_date": "", "end_date": ""}]
            - discharge_type: honorable, general, other-than-honorable, bad-conduct, dishonorable
            - character_of_service: honorable, general, etc.
            - service_connected_events: [{"description": "", "date": "", "location": ""}]

            If a field is not found in the document, set it to null.
            If no military service information is found at all, return: {"no_service_data": true}
            Return ONLY the JSON object, no other text.
            """;

    public ServiceRecordExtractorService(LlmJobService llmJobService) {
        this.llmJobService = llmJobService;
    }

    /**
     * Convenience: submit a service-record extraction job via LlmJobService.
     */
    public UUID submit(String documentText, String filename, Long claimId, Long evidenceId) {
        return llmJobService.submit(buildRequest(documentText, filename, claimId, evidenceId));
    }

    /**
     * Build a provider-agnostic LlmJobRequest for service record extraction.
     */
    public LlmJobRequest buildRequest(String documentText, String filename, Long claimId, Long evidenceId) {
        String userMessage = "Extract all military service record information from this document (" + filename + "):\n\n" + documentText;
        return LlmJobRequest.builder()
                .purpose("extraction_service_record")
                .systemPrompt(SERVICE_RECORD_SYSTEM_PROMPT)
                .userMessage(userMessage)
                .maxTokens(16384)
                .claimId(claimId)
                .userId(null)
                .evidenceId(evidenceId)
                .batchGroupKey("extraction_service_record_" + claimId)
                .build();
    }

    /**
     * Parse the LLM response into a list of AtomDtos.
     */
    public List<AtomDto> parseResponse(LlmJobResult result, String filename) {
        String text = result.getText();
        if (text == null || text.isBlank()) return List.of();
        return parseServiceRecordResponse(text, filename);
    }

    @SuppressWarnings("unchecked")
    private List<AtomDto> parseServiceRecordResponse(String responseText, String filename) {
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

            Map<String, Object> record = objectMapper.readValue(cleaned, Map.class);

            // Check for no-data sentinel
            if (Boolean.TRUE.equals(record.get("no_service_data"))) {
                log.info("No service record data found in evidence");
                return List.of();
            }

            List<AtomDto> atoms = new ArrayList<>();

            // Core service details
            addScalarAtom(atoms, record, "rank", "Rank", filename);
            addScalarAtom(atoms, record, "pay_grade", "Pay Grade", filename);
            addScalarAtom(atoms, record, "branch", "Branch of Service", filename);
            addScalarAtom(atoms, record, "mos_rating_afsc", "MOS/Rating/AFSC", filename);
            addScalarAtom(atoms, record, "discharge_type", "Discharge Type", filename);
            addScalarAtom(atoms, record, "character_of_service", "Character of Service", filename);

            // Service dates
            String enlistment = strOrNull(record, "enlistment_date");
            String separation = strOrNull(record, "separation_date");
            Object totalYears = record.get("total_service_years");
            if (enlistment != null || separation != null) {
                StringBuilder sb = new StringBuilder("Service Period:");
                if (enlistment != null) sb.append(" Enlistment: ").append(enlistment);
                if (separation != null) sb.append(" | Separation: ").append(separation);
                if (totalYears != null) sb.append(" | Total Years: ").append(totalYears);
                atoms.add(AtomDto.builder()
                        .type("service_record_detail")
                        .value(sb.toString())
                        .source(filename)
                        .confidence(0.95)
                        .date(enlistment)
                        .build());
            }

            // Deployments
            Object deploymentsObj = record.get("deployments");
            if (deploymentsObj instanceof List<?> deployments) {
                for (Object depObj : deployments) {
                    if (depObj instanceof Map<?, ?> dep) {
                        StringBuilder sb = new StringBuilder("Deployment:");
                        String loc = dep.get("location") != null ? dep.get("location").toString() : null;
                        if (loc != null) sb.append(" ").append(loc);
                        String depStart = dep.get("start_date") != null ? dep.get("start_date").toString() : null;
                        String depEnd = dep.get("end_date") != null ? dep.get("end_date").toString() : null;
                        if (depStart != null) sb.append(" | From: ").append(depStart);
                        if (depEnd != null) sb.append(" | To: ").append(depEnd);
                        Object combatZone = dep.get("combat_zone");
                        if (Boolean.TRUE.equals(combatZone)) sb.append(" | COMBAT ZONE");
                        atoms.add(AtomDto.builder()
                                .type("service_record_detail")
                                .value(sb.toString())
                                .source(filename)
                                .confidence(0.93)
                                .date(depStart)
                                .build());
                    }
                }
            }

            // Awards and decorations
            Object awardsObj = record.get("awards_decorations");
            if (awardsObj instanceof List<?> awards && !awards.isEmpty()) {
                StringBuilder sb = new StringBuilder("Awards/Decorations: ");
                List<String> awardNames = new ArrayList<>();
                for (Object a : awards) {
                    if (a != null) awardNames.add(a.toString());
                }
                sb.append(String.join(", ", awardNames));
                atoms.add(AtomDto.builder()
                        .type("service_record_detail")
                        .value(sb.toString())
                        .source(filename)
                        .confidence(0.93)
                        .date(null)
                        .build());
            }

            // Duty stations
            Object stationsObj = record.get("duty_stations");
            if (stationsObj instanceof List<?> stations) {
                for (Object stObj : stations) {
                    if (stObj instanceof Map<?, ?> station) {
                        StringBuilder sb = new StringBuilder("Duty Station:");
                        String name = station.get("name") != null ? station.get("name").toString() : null;
                        if (name != null) sb.append(" ").append(name);
                        String stStart = station.get("start_date") != null ? station.get("start_date").toString() : null;
                        String stEnd = station.get("end_date") != null ? station.get("end_date").toString() : null;
                        if (stStart != null) sb.append(" | From: ").append(stStart);
                        if (stEnd != null) sb.append(" | To: ").append(stEnd);
                        atoms.add(AtomDto.builder()
                                .type("service_record_detail")
                                .value(sb.toString())
                                .source(filename)
                                .confidence(0.93)
                                .date(stStart)
                                .build());
                    }
                }
            }

            // Service-connected events
            Object eventsObj = record.get("service_connected_events");
            if (eventsObj instanceof List<?> events) {
                for (Object evObj : events) {
                    if (evObj instanceof Map<?, ?> event) {
                        StringBuilder sb = new StringBuilder("Service-Connected Event:");
                        String desc = event.get("description") != null ? event.get("description").toString() : null;
                        if (desc != null) sb.append(" ").append(desc);
                        String evLoc = event.get("location") != null ? event.get("location").toString() : null;
                        if (evLoc != null) sb.append(" | Location: ").append(evLoc);
                        String evDate = event.get("date") != null ? event.get("date").toString() : null;
                        if (evDate != null) sb.append(" | Date: ").append(evDate);
                        atoms.add(AtomDto.builder()
                                .type("service_record_detail")
                                .value(sb.toString())
                                .source(filename)
                                .confidence(0.90)
                                .date(evDate)
                                .build());
                    }
                }
            }

            log.info("Parsed {} service record atoms from specialized extraction", atoms.size());
            return atoms;

        } catch (Exception e) {
            log.error("Failed to parse service record extraction response: {}", e.getMessage());
            log.debug("Raw response: {}", responseText);
            return List.of();
        }
    }

    private void addScalarAtom(List<AtomDto> atoms, Map<String, Object> record, String key, String label, String filename) {
        String val = strOrNull(record, key);
        if (val != null) {
            atoms.add(AtomDto.builder()
                    .type("service_record_detail")
                    .value(label + ": " + val)
                    .source(filename)
                    .confidence(0.95)
                    .date(null)
                    .build());
        }
    }

    private String strOrNull(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null || "null".equals(val.toString())) return null;
        String s = val.toString().strip();
        return s.isEmpty() ? null : s;
    }
}
