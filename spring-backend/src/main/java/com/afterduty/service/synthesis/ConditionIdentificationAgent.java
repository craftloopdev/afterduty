package com.afterduty.service.synthesis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.Atom;
import com.afterduty.service.llm.LlmJobRequest;
import com.afterduty.service.llm.LlmJobResult;
import com.afterduty.service.llm.LlmJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 3 Sub-Agent: Condition Identification.
 * Exposes buildRequest/parseResponse for use by SynthesisStateMachine.
 */
@Service
public class ConditionIdentificationAgent {

    private static final Logger log = LoggerFactory.getLogger(ConditionIdentificationAgent.class);

    /**
     * Identify prompt version — bumped to "3" for secondary-aware analysis
     * (38 CFR 3.310): the schema now emits {@code secondary_to} (the primary
     * condition's name) and the prompt reframes the in-service/nexus triad legs
     * for a secondary claim (in-service = "primary is service-connected", nexus =
     * causation/aggravation link). Version "2" added per-condition attribution via
     * {@code supporting_atom_ids} + the presumptive-nexus rule (Phase B item B2);
     * version "1" was the implicit pre-attribution prompt. Exposed via
     * {@link com.afterduty.config.PromptVersionRegistry} so the Increment 8
     * offline snapshot gate ({@code PromptVersionEvalGateTest}) forces a
     * deliberate snapshot regeneration (scored live run or explicit waiver) on
     * any future bump.
     */
    public static final String IDENTIFY_PROMPT_VERSION = "3";

    /**
     * Output budget for the condition list. Identify used to inherit the provider's
     * 16,000-token non-streaming default; on 2026-09-12 a 32-document corpus
     * (43,835 input tokens) overran it, the JSON was cut off mid-object, and the run
     * failed as "unparseable" — which a retry would repeat identically. 32K doubles
     * the room while keeping the worst-case generation (~5 min at observed rates)
     * inside the SDK's non-streaming timeout ceiling; the model's own limit is 128K.
     */
    public static final int MAX_OUTPUT_TOKENS = 32_768;

    private final LlmJobService llmJobService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You are a VA disability claims analyst. Given these extracted evidence atoms from a veteran's records, \
            identify ALL potential conditions for a VA disability claim.

            Each evidence atom below is labeled with its integer id as [atom N].

            For each condition:
            - name: official medical condition name
            - vasrd_code: 4-digit VASRD diagnostic code (must be valid)
            - body_system: VA body system category
            - is_presumptive: true if covered under PACT Act, Agent Orange, Gulf War, etc.
            - presumptive_basis: which presumption applies (null if not presumptive)
            - secondary_to: if this condition is claimed as SECONDARY to another, service-connected \
            condition (38 CFR 3.310 — e.g. GERD secondary to PTSD, sleep apnea secondary to PTSD, \
            a mental health condition secondary to a physical one), set this to the EXACT name of the \
            PRIMARY condition (match the primary's `name` if it also appears in your list). null for a \
            direct (non-secondary) claim.
            - triad_diagnosis: {"status": "STRONG|MODERATE|WEAK|MISSING", "evidence": ["list of evidence items"], "confidence": 0-1}
            - triad_in_service: {"status": "STRONG|MODERATE|WEAK|MISSING", "evidence": ["list"], "confidence": 0-1}
            - triad_nexus: {"status": "STRONG|MODERATE|WEAK|MISSING", "evidence": ["list"], "confidence": 0-1}
            - supporting_atom_ids: array of the integer atom ids (the N from the [atom N] labels) whose \
            evidence this condition rests on. Include EVERY atom you relied on for the triad assessment. \
            Use only ids that appear in the atom list; never invent ids.

            PRESUMPTIVE SERVICE CONNECTION: if the PRESUMPTIVE ELIGIBILITY section shows a presumption \
            that applies to this veteran's service for a condition, set is_presumptive to true with that \
            presumptive_basis, and score triad_nexus as STRONG with its evidence noted as \
            "[Presumptive under <basis>]". Presumptive service connection needs NO private medical nexus \
            opinion — do not treat a missing nexus letter as a weakness for a presumptive condition.

            SECONDARY SERVICE CONNECTION (38 CFR 3.310): a secondary condition has NO separate in-service \
            event — its in-service requirement is satisfied THROUGH the service-connected PRIMARY. So when \
            you set secondary_to, do NOT invent or force an in-service event: score triad_in_service as the \
            PRIMARY's service-connection status (STRONG only if the records show the primary is already VA \
            service-connected/rated; otherwise WEAK or MISSING with evidence like "Confirm <primary> is VA \
            service-connected — this secondary claim depends on it"). Score triad_nexus as the medical link \
            that the primary CAUSED or AGGRAVATED the secondary. Never assert the primary IS \
            service-connected unless the evidence says so.

            DO NOT assign ratings -- focus only on identification and triad assessment.
            Look for secondary conditions (e.g., sleep apnea secondary to PTSD).
            Look for bilateral conditions (both knees, both shoulders, etc.).
            Check for mental health conditions secondary to physical conditions.
            Identify conditions that may qualify for Individual Unemployability (TDIU).

            Return a JSON array of condition objects. Return ONLY the JSON array, no other text.
            """;

    public ConditionIdentificationAgent(LlmJobService llmJobService) {
        this.llmJobService = llmJobService;
    }

    /**
     * Convenience: submit a condition-identification job via LlmJobService.
     */
    public UUID submit(List<Atom> atoms, String serviceContext,
                       String presumptiveContext, Long claimId, Long userId) {
        return llmJobService.submit(buildRequest(atoms, serviceContext, presumptiveContext, claimId, userId));
    }

    /**
     * Build a provider-agnostic LlmJobRequest for condition identification.
     */
    public LlmJobRequest buildRequest(List<Atom> atoms, String serviceContext,
                                       String presumptiveContext, Long claimId, Long userId) {
        String atomSummary = buildAtomSummary(atoms);

        String userMessage = String.format("""
                Analyze the following extracted evidence atoms for a VA disability claim and identify all claimable conditions.

                === SERVICE CONTEXT ===
                %s

                === PRESUMPTIVE ELIGIBILITY ===
                %s

                === EXTRACTED EVIDENCE ATOMS (%d total) ===
                %s
                """, serviceContext, presumptiveContext, atoms.size(), atomSummary);

        return LlmJobRequest.builder()
                .purpose("synthesis_identify")
                .maxTokens(MAX_OUTPUT_TOKENS)
                .systemPrompt(SYSTEM_PROMPT)
                .userMessage(userMessage)
                .claimId(claimId)
                .userId(userId)
                .build();
    }

    /**
     * Parse the LLM response JSON array into a list of condition maps.
     * Returns {@code null} on an unparseable response — callers must treat that
     * as a failed run, never as a genuine "no conditions" answer (which is a
     * successfully parsed empty array).
     */
    public List<Map<String, Object>> parseResponse(LlmJobResult result) {
        try {
            String cleaned = cleanJsonResponse(result.getText());
            return objectMapper.readValue(cleaned, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            if (wasTruncated(result)) {
                log.error("Condition identification response was TRUNCATED at the {}-token output "
                        + "budget (stop_reason=max_tokens; {} output tokens) — not a malformed response",
                        MAX_OUTPUT_TOKENS, result.getOutputTokens());
            } else {
                log.error("Failed to parse condition identification response: {}", e.getMessage());
            }
            return null;
        }
    }

    /**
     * True when the provider stopped generating because the output budget ran out.
     * A truncated list is not a bad response — it is a corpus larger than the
     * budget, and the fix is more room or a leaner prompt, never a retry as-is.
     */
    public boolean wasTruncated(LlmJobResult result) {
        return result != null && result.getRaw() != null
                && "max_tokens".equals(result.getRaw().path("stop_reason").asText(null));
    }

    private String buildAtomSummary(List<Atom> atoms) {
        StringBuilder sb = new StringBuilder();
        Map<String, List<Atom>> byType = atoms.stream()
                .collect(Collectors.groupingBy(Atom::getType));

        for (Map.Entry<String, List<Atom>> entry : byType.entrySet()) {
            sb.append("\n## ").append(entry.getKey().toUpperCase())
              .append(" (").append(entry.getValue().size()).append(")\n");
            for (Atom atom : entry.getValue()) {
                // Prompt v2 — label each atom with its id so the model can cite it
                // in supporting_atom_ids (attribution at identify, item B2). Atoms
                // reaching identify are persisted rows, so the id is present; a
                // null id (bare unit-test construction) renders no label rather
                // than a fake one the model could cite.
                sb.append("- ");
                if (atom.getId() != null) sb.append("[atom ").append(atom.getId()).append("] ");
                sb.append(atom.getValue());
                if (atom.getTimestamp() != null) sb.append(" [").append(atom.getTimestamp()).append("]");
                sb.append(" (source: ").append(atom.getSource());
                sb.append(", confidence: ").append(String.format("%.2f", atom.getConfidence())).append(")\n");
            }
        }
        return sb.toString();
    }

    private String cleanJsonResponse(String text) {
        String cleaned = text.strip();
        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
        else if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
        return cleaned.strip();
    }
}
