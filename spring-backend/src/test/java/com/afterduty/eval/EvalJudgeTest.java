package com.afterduty.eval;

import com.afterduty.model.IdentifiedCondition;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for {@link EvalJudge} with a fake model caller (no live Gemini): the
 * rubric loads, the prompt includes the docs + narrative but never expected.json,
 * parse + re-ask + judge_error fallback work.
 */
@Tag("regression")
class EvalJudgeTest {

    private IdentifiedCondition ptsd() {
        return IdentifiedCondition.builder().claimId(1L).name("PTSD").vasrdCode("9411")
                .bodySystem("mental").estimatedRating(70)
                .ratingRationale("Reduced reliability and productivity per the note.").build();
    }

    @Test
    void parsesAStructuredJudgeResponse() {
        EvalJudge judge = new EvalJudge("gemini-3.1-pro-preview", prompt -> """
                {"hard_fails":{"legal_advice":false,"fabricated_citation":false},
                 "violations":[],
                 "scores":{"gap_completeness":0.8,"grounding":0.9,"plain_language":0.85},
                 "rationale":"grounded and clear"}""");
        JudgeScore s = judge.grade("gc-001", List.of("synthetic doc"), List.of(ptsd()));
        assertFalse(s.anyHardFail());
        assertEquals(0.9, s.scores().get("grounding"));
        assertEquals("gc-001", s.caseId());
        assertEquals("1", s.judgePromptVersion());
    }

    @Test
    void promptIncludesNarrativeButNotExpectations() {
        EvalJudge judge = new EvalJudge("m", p -> "{}");
        String prompt = judge.buildPrompt(List.of("SYNTH DOC BODY"), List.of(ptsd()));
        assertTrue(prompt.contains("SYNTH DOC BODY"), "source docs in prompt");
        assertTrue(prompt.contains("PTSD"), "condition narrative in prompt");
        assertTrue(prompt.contains("Reduced reliability"), "rationale in prompt");
        assertFalse(prompt.contains("must_be_found"), "expected.json fields must NOT leak to the judge");
    }

    @Test
    void reAsksOnceThenReturnsJudgeError() {
        AtomicInteger calls = new AtomicInteger();
        EvalJudge judge = new EvalJudge("m", p -> {
            calls.incrementAndGet();
            return "not json at all";
        });
        JudgeScore s = judge.grade("gc-009", List.of("doc"), List.of(ptsd()));
        assertEquals(2, calls.get(), "one initial call + one re-ask");
        assertTrue(s.rationale().startsWith("judge_error"));
    }

    @Test
    void detectsHardFail() {
        EvalJudge judge = new EvalJudge("m", p -> """
                {"hard_fails":{"legal_advice":true,"fabricated_citation":false},
                 "violations":[{"line":"no_legal_advice","quote":"You should appeal.","where":"gap:9411"}],
                 "scores":{"grounding":0.5}}""");
        JudgeScore s = judge.grade("gc-001", List.of("doc"), List.of(ptsd()));
        assertTrue(s.anyHardFail());
        assertEquals(1, s.violations().size());
    }
}
