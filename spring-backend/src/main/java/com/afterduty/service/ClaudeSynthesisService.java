package com.afterduty.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.afterduty.model.*;
import com.afterduty.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Layer 2: Synthesis, triad assessment, and gap analysis via Gemini 3.1 Pro Preview (Vertex AI REST API).
 */
@Service
public class ClaudeSynthesisService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeSynthesisService.class);

    private final AtomRepository atomRepository;
    private final ConditionRepository conditionRepository;
    private final ServiceProfileRepository serviceProfileRepository;
    private final PresumptiveRulesService presumptiveRulesService;
    private final VasrdDataService vasrdDataService;
    private final VaMathService vaMathService;
    private final AiCostService aiCostService;
    private final VasrdIndexService vasrdIndexService;
    private final com.afterduty.service.synthesis.PyramidingRules pyramidingRules;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private DomainCorrectionsService domainCorrectionsService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDomainCorrectionsService(DomainCorrectionsService svc) {
        this.domainCorrectionsService = svc;
    }
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${va-claim.gemini.project-id:}")
    private String projectId;

    @Value("${va-claim.gemini.location:global}")
    private String location;

    @Value("${va-claim.gemini.model:gemini-3.1-pro-preview}")
    private String modelName;

    @Value("${va-claim.gemini.thinking-budget:10240}")
    private int thinkingBudget;

    private GoogleCredentials credentials;

    private static final int MAX_OUTPUT_TOKENS = 65536;

    private static final String SYNTHESIS_SYSTEM_PROMPT = """
            You are a VA disability claims analysis AI. You analyze extracted evidence atoms and identify
            claimable conditions with full triad assessment.

            For each condition you identify, provide:

            1. **Condition name** - The specific medical condition
            2. **VASRD diagnostic code** - The 38 CFR Part 4 code (e.g., "5201" for shoulder limitation)
            3. **Body system** - The VASRD body system category
            4. **Triad assessment**:
               - **Diagnosis**: Current medical diagnosis evidence (status: strong/moderate/weak, evidence list, confidence 0-1)
               - **In-service event/occurrence**: Evidence of in-service incurrence or aggravation (status: strong/moderate/weak, evidence list, confidence 0-1)
               - **Nexus**: Medical nexus linking current condition to service (status: strong/moderate/weak, evidence list, confidence 0-1)
            5. **Presumptive basis** - If applicable (PACT Act, Agent Orange, Gulf War, etc.)
            6. **Estimated rating** - Based on VASRD criteria (0, 10, 20, 30, 40, 50, 60, 70, 80, 100)
            7. **Rating rationale** - Why this rating level based on documented severity/functional limitations
            8. **Confidence** - Overall confidence in this condition being ratable (0-1)

            IMPORTANT RULES:
            - Identify ALL potentially claimable conditions, including secondary conditions
            - Check for presumptive conditions (PACT Act burn pit, Agent Orange, Gulf War)
            - Consider bilateral conditions (both knees, both shoulders, etc.)
            - Look for mental health conditions secondary to physical conditions
            - Identify conditions that may qualify for Individual Unemployability (TDIU)
            - Be conservative with ratings -- base on documented evidence only
            - Use the CANONICAL VASRD CODES section supplied in the user message
              to pick the correct `vasrd_code` and `body_system` strings. Prefer
              the canonical spelling shown there when naming the condition
            - Flag conditions needing additional evidence

            Return a JSON array of condition objects. Each condition must have:
            {
              "name": "<condition name>",
              "vasrd_code": "<4-digit VASRD code>",
              "body_system": "<body system>",
              "triad_diagnosis": {"status": "<strong|moderate|weak>", "evidence": ["<evidence items>"], "confidence": <0-1>},
              "triad_in_service": {"status": "<strong|moderate|weak>", "evidence": ["<evidence items>"], "confidence": <0-1>},
              "triad_nexus": {"status": "<strong|moderate|weak>", "evidence": ["<evidence items>"], "confidence": <0-1>},
              "is_presumptive": <true|false>,
              "presumptive_basis": "<basis or null>",
              "estimated_rating": <0-100>,
              "rating_rationale": "<explanation>",
              "confidence": <0-1>
            }

            Return ONLY the JSON array, no other text.
            """;

    private static final String GAP_ANALYSIS_SYSTEM_PROMPT = """
            You are a VA disability claims gap analysis AI. Analyze the identified conditions and their
            evidence to find gaps and what-if scenarios.

            For each condition, identify:

            1. **Evidence gaps** - What's missing to strengthen the claim:
               - Missing nexus letter
               - Missing buddy statements
               - Incomplete medical records
               - Missing C&P exam findings
               - Missing service treatment records
               - Missing specialist evaluations
               Each gap should have: type, description, priority (high/medium/low), impact on claim

            2. **What-if scenarios** - How the rating could change:
               - "If nexus letter obtained" -> estimated new rating and monthly compensation delta
               - "If buddy statements filed" -> estimated impact
               - "If C&P exam shows X severity" -> potential rating increase
               Each scenario should have: scenario description, current_rating, potential_rating, monthly_delta, action_required

            Return a JSON object with condition IDs as keys:
            {
              "<condition_name>": {
                "gaps": [
                  {"type": "<gap_type>", "description": "<what's needed>", "priority": "<high|medium|low>", "impact": "<expected impact>"}
                ],
                "what_if_scenarios": [
                  {"scenario": "<description>", "current_rating": <int>, "potential_rating": <int>, "monthly_delta": <double>, "action_required": "<what to do>"}
                ]
              }
            }

            Return ONLY the JSON object, no other text.
            """;

    public ClaudeSynthesisService(AtomRepository atomRepository, ConditionRepository conditionRepository,
                                  ServiceProfileRepository serviceProfileRepository, PresumptiveRulesService presumptiveRulesService,
                                  VasrdDataService vasrdDataService, VaMathService vaMathService,
                                  AiCostService aiCostService, VasrdIndexService vasrdIndexService,
                                  com.afterduty.service.synthesis.PyramidingRules pyramidingRules) {
        this.atomRepository = atomRepository;
        this.conditionRepository = conditionRepository;
        this.serviceProfileRepository = serviceProfileRepository;
        this.presumptiveRulesService = presumptiveRulesService;
        this.vasrdDataService = vasrdDataService;
        this.vaMathService = vaMathService;
        this.aiCostService = aiCostService;
        this.vasrdIndexService = vasrdIndexService;
        this.pyramidingRules = pyramidingRules;
    }

    @PostConstruct
    public void init() {
        try {
            credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped("https://www.googleapis.com/auth/cloud-platform");
            log.info("Initialized synthesis service (Gemini REST, project={}, location={}, model={})",
                    projectId, location, modelName);
        } catch (Exception e) {
            log.error("Failed to initialize Google credentials for synthesis: {}", e.getMessage(), e);
        }
    }

    /**
     * Run synthesis: analyze all atoms and identify conditions with triad assessment.
     */
    public String runSynthesis(Long claimId, Long userId) {
        log.info("Running synthesis for claim {} (user {})", claimId, userId);

        // Mission 5a: legacy sync synthesis path also reasons over LIVE atoms only
        // (superseded atoms from a re-extracted doc must not re-enter synthesis).
        List<Atom> atoms = atomRepository.findByClaimIdAndSupersededByIsNull(claimId);
        if (atoms.isEmpty()) {
            return "{\"note\": \"No atoms to synthesize\"}";
        }

        String atomSummary = buildAtomSummary(atoms);
        String serviceContext = buildServiceContext(userId);
        String presumptiveContext = buildPresumptiveContext(userId);

        String kbDigest = vasrdIndexService != null ? vasrdIndexService.toPromptDigest() : "";

        String userMessage = String.format("""
                Analyze the following extracted evidence atoms for a VA disability claim and identify all claimable conditions.

                === SERVICE CONTEXT ===
                %s

                === PRESUMPTIVE ELIGIBILITY ===
                %s

                === CANONICAL VASRD CODES (use these for vasrd_code / body_system / name) ===
                %s

                === EXTRACTED EVIDENCE ATOMS (%d total) ===
                %s
                """, serviceContext, presumptiveContext, kbDigest, atoms.size(), atomSummary);

        try {
            String responseText = callGemini(SYNTHESIS_SYSTEM_PROMPT, userMessage, claimId, userId, "synthesis");
            log.info("Synthesis raw response ({} chars): {}", responseText.length(),
                    responseText.substring(0, Math.min(500, responseText.length())));
            List<Map<String, Object>> conditions = parseJsonArray(responseText);
            log.info("Parsed {} conditions from synthesis response", conditions.size());

            int created = 0;
            List<IdentifiedCondition> saved = new ArrayList<>();
            for (Map<String, Object> condMap : conditions) {
                try {
                    IdentifiedCondition condition = mapToCondition(condMap, claimId);
                    saved.add(conditionRepository.save(condition));
                    created++;
                } catch (Exception e) {
                    log.warn("Failed to save condition from synthesis: {}", e.getMessage());
                }
            }

            // Self-correction KB — the legacy admin force-run lane must honor the
            // same guarantee as the live state machine (a feedback-disproven
            // claim never ships, whichever lane asserted it).
            if (domainCorrectionsService != null) {
                int repaired = domainCorrectionsService.enforce(saved);
                if (repaired > 0) {
                    for (IdentifiedCondition c : saved) conditionRepository.save(c);
                    log.info("Domain corrections repaired {} condition(s) on the legacy synthesis lane", repaired);
                }
            }

            log.info("Synthesis complete: {} atoms analyzed, {} conditions created", atoms.size(), created);
            return String.format("{\"atoms_analyzed\": %d, \"conditions_created\": %d}", atoms.size(), created);

        } catch (Exception e) {
            log.error("Synthesis failed for claim {}: {}", claimId, e.getMessage(), e);
            return String.format("{\"error\": \"%s\"}", e.getMessage());
        }
    }

    /**
     * Run gap analysis on synthesized conditions.
     */
    public String runGapAnalysis(Long claimId, Long userId) {
        log.info("Running gap analysis for claim {} (user {})", claimId, userId);

        // Mission 5b — analyze the ACTIVE generation's conditions only.
        List<IdentifiedCondition> conditions = conditionRepository.findByClaimIdAndSupersededByIsNull(claimId);
        if (conditions.isEmpty()) {
            return "{\"note\": \"No conditions to analyze gaps for\"}";
        }

        String conditionsSummary = buildConditionsSummary(conditions);

        String userMessage = String.format("""
                Analyze the following identified VA disability conditions and find evidence gaps and what-if scenarios.

                === IDENTIFIED CONDITIONS (%d total) ===
                %s
                """, conditions.size(), conditionsSummary);

        try {
            String responseText = callGemini(GAP_ANALYSIS_SYSTEM_PROMPT, userMessage, claimId, userId, "gap_analysis");
            Map<String, Object> gapResults = parseJsonObject(responseText);

            for (IdentifiedCondition condition : conditions) {
                @SuppressWarnings("unchecked")
                Map<String, Object> condGaps = (Map<String, Object>) gapResults.get(condition.getName());
                if (condGaps == null) {
                    for (Map.Entry<String, Object> entry : gapResults.entrySet()) {
                        if (condition.getName().toLowerCase().contains(entry.getKey().toLowerCase()) ||
                                entry.getKey().toLowerCase().contains(condition.getName().toLowerCase())) {
                            condGaps = (Map<String, Object>) entry.getValue();
                            break;
                        }
                    }
                }

                if (condGaps != null) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> gaps = (List<Map<String, Object>>) condGaps.get("gaps");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> whatIfs = (List<Map<String, Object>>) condGaps.get("what_if_scenarios");

                    if (gaps != null) condition.setGaps(gaps);
                    if (whatIfs != null) condition.setWhatIfScenarios(whatIfs);
                    conditionRepository.save(condition);
                }
            }

            // Apply VA pyramiding rules before combining: drops conditions
            // Claude flagged as absorbed (mental-health collapse, etc.),
            // caps tinnitus at 10%, and detects bilateral pairs so the
            // §4.26 +10% factor is applied. Notes are logged so we can
            // see exactly which rules fired for a given claim.
            com.afterduty.service.synthesis.PyramidingRules.Plan plan =
                    pyramidingRules.plan(conditions);
            for (String note : plan.notes()) {
                log.info("[pyramiding] claim {}: {}", claimId, note);
            }
            Map<String, Object> combined = vaMathService.calculateCombinedRating(
                    plan.ratings(), plan.bilateralPairs());

            log.info("Gap analysis complete: {} conditions analyzed (after pyramiding), combined rating: {}",
                    plan.ratings().size() + plan.bilateralPairs().size() * 2,
                    combined.get("combined_rating"));
            return String.format("{\"conditions_analyzed\": %d, \"combined_rating\": %s}",
                    conditions.size(), combined.get("combined_rating"));

        } catch (Exception e) {
            log.error("Gap analysis failed for claim {}: {}", claimId, e.getMessage(), e);
            return String.format("{\"error\": \"%s\"}", e.getMessage());
        }
    }

    /**
     * Call Gemini 3.1 Pro via Vertex AI REST API with thinking.
     */
    private String callGemini(String systemPrompt, String userMessage, Long claimId, Long userId, String callType) {
        long startMs = System.currentTimeMillis();
        AiCallLog.AiCallLogBuilder logBuilder = AiCallLog.builder()
                .claimId(claimId)
                .userId(userId)
                .callType(callType)
                .provider("gemini")
                .modelName(modelName);

        try {
            // Build request body
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("systemInstruction", Map.of(
                    "parts", List.of(Map.of("text", systemPrompt))
            ));
            requestBody.put("contents", List.of(Map.of(
                    "role", "user",
                    "parts", List.of(Map.of("text", userMessage))
            )));

            Map<String, Object> genConfig = new LinkedHashMap<>();
            genConfig.put("temperature", 0.2);
            genConfig.put("maxOutputTokens", MAX_OUTPUT_TOKENS);
            // Don't use responseMimeType with thinking — Gemini may return empty output
            genConfig.put("thinkingConfig", Map.of("thinkingBudget", thinkingBudget));
            requestBody.put("generationConfig", genConfig);

            String requestJson = objectMapper.writeValueAsString(requestBody);

            credentials.refreshIfExpired();
            String accessToken = credentials.getAccessToken().getTokenValue();

            String url = String.format(
                    "https://aiplatform.googleapis.com/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent",
                    projectId, location, modelName);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = System.currentTimeMillis() - startMs;

            if (response.statusCode() == 429) {
                // Rate limited — retry with backoff
                logBuilder.latencyMs(latencyMs);
                aiCostService.recordError(logBuilder.build(), "Rate limited (429)");
                log.warn("Gemini 429 for {} — retrying in 60s", callType);
                try { Thread.sleep(60_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                return callGemini(systemPrompt, userMessage, claimId, userId, callType);
            }

            if (response.statusCode() != 200) {
                String errorMsg = "Gemini API returned " + response.statusCode() + ": " +
                        response.body().substring(0, Math.min(500, response.body().length()));
                logBuilder.latencyMs(latencyMs);
                aiCostService.recordError(logBuilder.build(), errorMsg);
                throw new RuntimeException("Gemini API call failed: " + errorMsg);
            }

            JsonNode responseJson = objectMapper.readTree(response.body());

            // Extract token usage
            JsonNode usageNode = responseJson.path("usageMetadata");
            long inputTokens = usageNode.path("promptTokenCount").asLong(0);
            long outputTokens = usageNode.path("candidatesTokenCount").asLong(0);
            long thinkingTokens = usageNode.path("thoughtsTokenCount").asLong(0);
            long totalTokens = usageNode.path("totalTokenCount").asLong(0);
            if (thinkingTokens == 0 && totalTokens > inputTokens + outputTokens) {
                thinkingTokens = totalTokens - inputTokens - outputTokens;
            }

            logBuilder.inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .thinkingTokens(thinkingTokens)
                    .latencyMs(latencyMs)
                    .status("success");
            aiCostService.recordCall(logBuilder.build());

            // Extract text response (skip thinking parts)
            String responseText = null;
            JsonNode candidates = responseJson.path("candidates");
            log.debug("Gemini response candidates count: {}", candidates.size());
            if (!candidates.isEmpty()) {
                JsonNode parts = candidates.get(0).path("content").path("parts");
                log.debug("Response parts count: {}", parts.size());
                for (JsonNode part : parts) {
                    boolean isThought = part.path("thought").asBoolean(false);
                    boolean hasText = part.has("text");
                    log.debug("Part: hasText={}, isThought={}, textLength={}",
                            hasText, isThought, hasText ? part.get("text").asText().length() : 0);
                    if (hasText && !isThought) {
                        responseText = part.get("text").asText();
                    }
                }
            }

            if (responseText == null || responseText.isBlank()) {
                // Log the full response for debugging
                log.error("Empty response from Gemini for {}. Full response: {}",
                        callType, response.body().substring(0, Math.min(2000, response.body().length())));
                throw new RuntimeException("Empty response from Gemini");
            }

            log.info("Gemini {} response: {} chars", callType, responseText.length());
            return responseText;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startMs;
            logBuilder.latencyMs(latencyMs);
            aiCostService.recordError(logBuilder.build(), e.getMessage());
            throw new RuntimeException("Gemini API call failed: " + e.getMessage(), e);
        }
    }

    private String buildAtomSummary(List<Atom> atoms) {
        StringBuilder sb = new StringBuilder();
        Map<String, List<Atom>> byType = atoms.stream()
                .collect(Collectors.groupingBy(Atom::getType));

        for (Map.Entry<String, List<Atom>> entry : byType.entrySet()) {
            sb.append("\n## ").append(entry.getKey().toUpperCase()).append(" (").append(entry.getValue().size()).append(")\n");
            for (Atom atom : entry.getValue()) {
                sb.append("- ").append(atom.getValue());
                if (atom.getTimestamp() != null) sb.append(" [").append(atom.getTimestamp()).append("]");
                sb.append(" (source: ").append(atom.getSource());
                sb.append(", confidence: ").append(String.format("%.2f", atom.getConfidence())).append(")\n");
            }
        }
        return sb.toString();
    }

    private String buildServiceContext(Long userId) {
        return serviceProfileRepository.findByUserId(userId)
                .map(profile -> {
                    StringBuilder sb = new StringBuilder();
                    if (profile.getBranch() != null) sb.append("Branch: ").append(profile.getBranch()).append("\n");
                    if (profile.getServiceStart() != null) sb.append("Service start: ").append(profile.getServiceStart()).append("\n");
                    if (profile.getServiceEnd() != null) sb.append("Service end: ").append(profile.getServiceEnd()).append("\n");
                    if (profile.getMos() != null) sb.append("MOS: ").append(profile.getMos()).append("\n");
                    if (profile.getDeployments() != null) sb.append("Deployments: ").append(profile.getDeployments()).append("\n");
                    if (profile.getExposureRisks() != null) sb.append("Exposure risks: ").append(profile.getExposureRisks()).append("\n");
                    return sb.toString();
                })
                .orElse("No service profile on file.\n");
    }

    private String buildPresumptiveContext(Long userId) {
        return serviceProfileRepository.findByUserId(userId)
                .map(profile -> {
                    Map<String, Object> profileMap = Map.of(
                            "deployments", profile.getDeployments() != null ? profile.getDeployments() : List.of(),
                            "exposure_risks", profile.getExposureRisks() != null ? profile.getExposureRisks() : List.of(),
                            "service_start", profile.getServiceStart() != null ? profile.getServiceStart() : "",
                            "service_end", profile.getServiceEnd() != null ? profile.getServiceEnd() : ""
                    );
                    List<Map<String, String>> matches = presumptiveRulesService.checkPresumptiveConnections(profileMap);
                    if (matches.isEmpty()) return "No presumptive conditions matched.\n";

                    StringBuilder sb = new StringBuilder();
                    sb.append("Veteran may qualify for ").append(matches.size()).append(" presumptive conditions:\n");
                    for (Map<String, String> match : matches) {
                        sb.append("- ").append(match.get("condition"))
                                .append(" (VASRD ").append(match.get("vasrd_code"))
                                .append(", basis: ").append(match.get("basis")).append(")\n");
                    }
                    return sb.toString();
                })
                .orElse("No service profile -- cannot check presumptive eligibility.\n");
    }

    private String buildConditionsSummary(List<IdentifiedCondition> conditions) {
        StringBuilder sb = new StringBuilder();
        for (IdentifiedCondition c : conditions) {
            sb.append("\n### ").append(c.getName());
            if (c.getVasrdCode() != null) sb.append(" (VASRD ").append(c.getVasrdCode()).append(")");
            sb.append("\n");
            sb.append("Body system: ").append(c.getBodySystem()).append("\n");
            sb.append("Estimated rating: ").append(c.getEstimatedRating()).append("%\n");
            sb.append("Confidence: ").append(String.format("%.2f", c.getConfidence())).append("\n");
            if (c.getTriadDiagnosis() != null) sb.append("Diagnosis triad: ").append(c.getTriadDiagnosis()).append("\n");
            if (c.getTriadInService() != null) sb.append("In-service triad: ").append(c.getTriadInService()).append("\n");
            if (c.getTriadNexus() != null) sb.append("Nexus triad: ").append(c.getTriadNexus()).append("\n");
            if (c.getRatingRationale() != null) sb.append("Rationale: ").append(c.getRatingRationale()).append("\n");
            if (Boolean.TRUE.equals(c.getIsPresumptive())) {
                sb.append("PRESUMPTIVE: ").append(c.getPresumptiveBasis()).append("\n");
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private IdentifiedCondition mapToCondition(Map<String, Object> condMap, Long claimId) {
        return IdentifiedCondition.builder()
                .claimId(claimId)
                .name((String) condMap.getOrDefault("name", "Unknown Condition"))
                .vasrdCode((String) condMap.get("vasrd_code"))
                .bodySystem((String) condMap.get("body_system"))
                .triadDiagnosis((Map<String, Object>) condMap.get("triad_diagnosis"))
                .triadInService((Map<String, Object>) condMap.get("triad_in_service"))
                .triadNexus((Map<String, Object>) condMap.get("triad_nexus"))
                .isPresumptive(condMap.get("is_presumptive") instanceof Boolean b ? b : false)
                .presumptiveBasis((String) condMap.get("presumptive_basis"))
                // Secondary-aware analysis (38 CFR 3.310): the primary condition's
                // name when secondary, else null. Blank ⇒ null (direct claim).
                .secondaryTo(condMap.get("secondary_to") instanceof String s && !s.isBlank() ? s.strip() : null)
                .estimatedRating(condMap.get("estimated_rating") instanceof Number n ? n.intValue() : 0)
                .ratingRationale((String) condMap.get("rating_rationale"))
                .confidence(condMap.get("confidence") instanceof Number n ? n.doubleValue() : 0.5)
                .build();
    }

    private List<Map<String, Object>> parseJsonArray(String text) {
        try {
            String cleaned = cleanJsonResponse(text);
            return objectMapper.readValue(cleaned, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.error("Failed to parse JSON array from response: {}", e.getMessage());
            log.debug("Raw response: {}", text);
            return List.of();
        }
    }

    private Map<String, Object> parseJsonObject(String text) {
        try {
            String cleaned = cleanJsonResponse(text);
            return objectMapper.readValue(cleaned, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("Failed to parse JSON object from response: {}", e.getMessage());
            log.debug("Raw response: {}", text);
            return Map.of();
        }
    }

    private String cleanJsonResponse(String text) {
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
}
