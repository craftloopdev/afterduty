package com.afterduty.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.afterduty.service.ServiceHistoryAdjudicationService.Adjudication;
import com.afterduty.service.ServiceHistoryAdjudicationService.Adjudicator;
import com.afterduty.service.ServiceHistoryReconciler.Conflict;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Production {@link Adjudicator} — resolves a genuine equal-authority service-history
 * date conflict via Gemini on Vertex AI (same REST pattern as
 * {@code ClaudeSynthesisService.callGemini}). Deliberately tiny: it asks the model to
 * pick ONE of the two contradictory ISO dates and give a one-sentence reason, and it
 * FORCES the chosen value back onto the exact supplied set (the model may only choose
 * between the two — it never invents a date). Returns {@link Optional#empty()} on any
 * failure so the deterministic pick stands (the adjudication step is best-effort).
 *
 * <p>Invoked ONLY at pipeline time by {@link ServiceHistoryAdjudicationService}, and
 * only when a genuine conflict was flagged — so this makes no LLM call in the common
 * case. Never called from the read-time profile endpoint.
 */
@Component
public class GeminiServiceHistoryAdjudicator implements Adjudicator {

    private static final Logger log = LoggerFactory.getLogger(GeminiServiceHistoryAdjudicator.class);

    private static final int MAX_OUTPUT_TOKENS = 2048;

    private static final String SYSTEM_PROMPT = """
            You adjudicate conflicts in a veteran's military service dates. Two documents of
            EQUAL evidentiary weight disagree on a single date (an enlistment or separation
            date). Decide which of the TWO given dates is more likely correct and explain
            briefly, in plain language a veteran can understand.

            Rules:
            - Choose EXACTLY ONE of the two provided dates. Never invent a third date.
            - Prefer the value most consistent with a real, contiguous term of service.
            - Keep the reason to one short sentence.

            Return ONLY a JSON object, no other text:
            { "chosen": "<YYYY-MM-DD, exactly one of the two provided>", "reason": "<one sentence>" }
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${va-claim.gemini.project-id:}")
    private String projectId;

    @Value("${va-claim.gemini.location:global}")
    private String location;

    @Value("${va-claim.gemini.model:gemini-3.1-pro-preview}")
    private String modelName;

    private GoogleCredentials credentials;

    @PostConstruct
    public void init() {
        try {
            credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped("https://www.googleapis.com/auth/cloud-platform");
        } catch (Exception e) {
            // No credentials (local/test) — adjudicate() will no-op, deterministic pick stands.
            log.warn("GeminiServiceHistoryAdjudicator: no credentials ({}); adjudication will no-op",
                    e.getMessage());
        }
    }

    @Override
    public Optional<Adjudication> adjudicate(Conflict conflict, Long claimId, Long userId) {
        if (credentials == null) return Optional.empty();
        try {
            String userMessage = String.format(Locale.ROOT, """
                    Field in conflict: %s date.
                    Option A: %s
                    Option B: %s

                    Choose the correct value (exactly one of the two above) and give one sentence why.
                    """, conflict.field(), conflict.valueA(), conflict.valueB());

            String responseText = callGemini(userMessage);
            if (responseText == null) return Optional.empty();

            JsonNode obj = objectMapper.readTree(cleanJson(responseText));
            String chosen = obj.path("chosen").asText(null);
            String reason = obj.path("reason").asText(null);
            if (chosen == null) return Optional.empty();
            chosen = chosen.strip();
            // Force onto the supplied set — the service also re-validates, but reject early.
            if (!chosen.equals(conflict.valueA()) && !chosen.equals(conflict.valueB())) {
                return Optional.empty();
            }
            String why = reason != null && !reason.isBlank() ? reason.strip()
                    : "equal-authority date conflict";
            return Optional.of(new Adjudication(chosen, why));
        } catch (Exception e) {
            log.warn("Service-history adjudication LLM call failed for user {}: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    /** One Gemini generateContent call; returns the text response or null on non-200. */
    private String callGemini(String userMessage) throws Exception {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("systemInstruction", Map.of("parts", List.of(Map.of("text", SYSTEM_PROMPT))));
        requestBody.put("contents", List.of(Map.of(
                "role", "user", "parts", List.of(Map.of("text", userMessage)))));
        Map<String, Object> genConfig = new LinkedHashMap<>();
        genConfig.put("temperature", 0.0);
        genConfig.put("maxOutputTokens", MAX_OUTPUT_TOKENS);
        requestBody.put("generationConfig", genConfig);

        credentials.refreshIfExpired();
        String accessToken = credentials.getAccessToken().getTokenValue();
        String url = String.format(
                "https://aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent",
                projectId, location, modelName);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("Service-history adjudication Gemini returned {}", response.statusCode());
            return null;
        }
        JsonNode responseJson = objectMapper.readTree(response.body());
        JsonNode candidates = responseJson.path("candidates");
        if (candidates.isEmpty()) return null;
        JsonNode parts = candidates.get(0).path("content").path("parts");
        for (JsonNode part : parts) {
            if (part.has("text") && !part.path("thought").asBoolean(false)) {
                return part.get("text").asText();
            }
        }
        return null;
    }

    private static String cleanJson(String text) {
        String c = text.strip();
        if (c.startsWith("```json")) c = c.substring(7);
        else if (c.startsWith("```")) c = c.substring(3);
        if (c.endsWith("```")) c = c.substring(0, c.length() - 3);
        return c.strip();
    }
}
