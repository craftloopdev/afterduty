package com.afterduty.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * The cross-family judge's structured verdict for one case (spec §3.4). The judge
 * (Gemini) grades only what needs judgment over the Claude-generated narrative;
 * the deterministic scorer owns everything verifiable.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record JudgeScore(
        @JsonProperty("case_id") String caseId,
        @JsonProperty("judge_model") String judgeModel,
        @JsonProperty("judge_prompt_version") String judgePromptVersion,
        @JsonProperty("hard_fails") HardFails hardFails,
        List<Violation> violations,
        Map<String, Double> scores,
        String rationale
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HardFails(
            @JsonProperty("legal_advice") Boolean legalAdvice,
            @JsonProperty("fabricated_citation") Boolean fabricatedCitation
    ) {
        public boolean any() {
            return Boolean.TRUE.equals(legalAdvice) || Boolean.TRUE.equals(fabricatedCitation);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Violation(String line, String quote, String where) {}

    public boolean anyHardFail() {
        return hardFails != null && hardFails.any();
    }

    /** An error placeholder when the judge response could not be parsed twice. */
    public static JudgeScore error(String caseId, String judgeModel, String reason) {
        return new JudgeScore(caseId, judgeModel, "1",
                new HardFails(false, false), List.of(),
                Map.of(), "judge_error: " + reason);
    }
}
