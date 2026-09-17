package com.afterduty.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.IdentifiedCondition;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Cross-family LLM judge (spec §3.4). Builds the judge prompt from the versioned
 * rubric ({@code golden/judge/judge-prompt-v1.md}) plus the synthetic source docs
 * and the end-state NARRATIVE artifacts (condition names, rating rationales, gap
 * descriptions, what-if text) — NEVER {@code expected.json} (independence; the
 * deterministic scorer owns expectations).
 *
 * <p>The actual model call is injected as a {@code Function<String,String>}
 * (prompt → raw response) so the judge is unit-testable with a fake responder and,
 * in the live runner, bound to a one-shot {@code VertexGeminiAsyncProviderImpl}
 * call (livesmoke construction pattern). One re-ask on parse failure, then
 * {@link JudgeScore#error}.
 */
public final class EvalJudge {

    public static final String JUDGE_PROMPT_RESOURCE = "golden/judge/judge-prompt-v1.md";
    public static final String JUDGE_PROMPT_VERSION = "1";

    private final ObjectMapper mapper = new ObjectMapper();
    private final String rubric;
    private final String judgeModel;
    private final Function<String, String> caller;

    /**
     * @param judgeModel the judge model id (recorded per run)
     * @param caller     prompt → raw model response (JSON-only instructed)
     */
    public EvalJudge(String judgeModel, Function<String, String> caller) {
        this.judgeModel = judgeModel;
        this.caller = caller;
        this.rubric = loadRubric();
    }

    /** Grade one case. {@code docs} are the synthetic source bodies. */
    public JudgeScore grade(String caseId, List<String> docs, List<IdentifiedCondition> active) {
        String prompt = buildPrompt(docs, active);
        String raw = caller.apply(prompt);
        JudgeScore parsed = tryParse(caseId, raw);
        if (parsed != null) return parsed;
        // one re-ask appending an explicit JSON-only nudge
        String raw2 = caller.apply(prompt + "\n\nReturn ONLY the JSON object, no prose.");
        JudgeScore parsed2 = tryParse(caseId, raw2);
        return parsed2 != null ? parsed2 : JudgeScore.error(caseId, judgeModel, "unparseable judge response");
    }

    String buildPrompt(List<String> docs, List<IdentifiedCondition> active) {
        StringBuilder sb = new StringBuilder(rubric);
        sb.append("\n\n=== SYNTHETIC SOURCE DOCUMENTS ===\n");
        for (String d : docs) sb.append(d).append("\n---\n");
        sb.append("\n=== ANALYSIS OUTPUT TO GRADE ===\n");
        for (IdentifiedCondition c : active) {
            sb.append("Condition: ").append(c.getName())
                    .append(" (DC ").append(c.getVasrdCode()).append("), rated ")
                    .append(c.getEstimatedRating()).append("%\n");
            if (c.getRatingRationale() != null) sb.append("  Rationale: ").append(c.getRatingRationale()).append("\n");
            if (c.getGaps() != null) {
                for (Map<String, Object> g : c.getGaps()) sb.append("  Gap: ").append(g).append("\n");
            }
            if (c.getWhatIfScenarios() != null) {
                for (Map<String, Object> w : c.getWhatIfScenarios()) sb.append("  What-if: ").append(w).append("\n");
            }
        }
        sb.append("\nGrade per the rubric. Return ONLY the JSON object described above.\n");
        return sb.toString();
    }

    private JudgeScore tryParse(String caseId, String raw) {
        try {
            String cleaned = clean(raw);
            JudgeScore s = mapper.readValue(cleaned, JudgeScore.class);
            // stamp model + version if the judge omitted them
            return new JudgeScore(caseId, judgeModel, JUDGE_PROMPT_VERSION,
                    s.hardFails() == null ? new JudgeScore.HardFails(false, false) : s.hardFails(),
                    s.violations() == null ? List.of() : s.violations(),
                    s.scores() == null ? Map.of() : s.scores(),
                    s.rationale());
        } catch (Exception e) {
            return null;
        }
    }

    private static String clean(String text) {
        if (text == null) return "{}";
        String c = text.strip();
        if (c.startsWith("```json")) c = c.substring(7);
        else if (c.startsWith("```")) c = c.substring(3);
        if (c.endsWith("```")) c = c.substring(0, c.length() - 3);
        return c.strip();
    }

    private String loadRubric() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(JUDGE_PROMPT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("judge prompt resource not found: " + JUDGE_PROMPT_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load judge prompt", e);
        }
    }
}
