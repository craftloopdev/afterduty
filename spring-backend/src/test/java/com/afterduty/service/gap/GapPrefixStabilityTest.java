package com.afterduty.service.gap;

import com.afterduty.model.Atom;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.service.llm.LlmJobRequest;
import com.afterduty.service.llm.LlmJobService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mission 6b — GAP FAN-OUT stable-prefix coverage for {@link EvidenceGapAnalyzer} and
 * {@link GapValidationAgent}. Both fold the entire atom corpus per-condition today; the
 * restructure makes the LIVE atom corpus a single byte-stable cached prefix shared by
 * every condition in a run, with the per-condition payload on the volatile tail. WhatIf
 * is intentionally excluded — it never resends the corpus.
 */
@Tag("regression")
class GapPrefixStabilityTest {

    private static void setCaching(Object agent, boolean caching) throws Exception {
        Field f = agent.getClass().getDeclaredField("promptCachingEnabled");
        f.setAccessible(true);
        f.set(agent, caching);
    }

    private Atom atom(Long id, Long evidenceId, String type, String value) {
        Atom a = new Atom();
        a.setId(id);
        a.setEvidenceId(evidenceId);
        a.setType(type);
        a.setValue(value);
        a.setSource("doc-" + evidenceId);
        a.setConfidence(0.85);
        return a;
    }

    private List<Atom> corpus() {
        List<Atom> atoms = new ArrayList<>();
        atoms.add(atom(1L, 10L, "diagnosis", "asthma"));
        atoms.add(atom(2L, 10L, "medication", "albuterol"));
        atoms.add(atom(3L, 11L, "symptom", "shortness of breath"));
        return atoms;
    }

    private IdentifiedCondition condition(Long id, String name, String code, Integer rating) {
        IdentifiedCondition c = new IdentifiedCondition();
        c.setId(id);
        c.setName(name);
        c.setVasrdCode(code);
        c.setEstimatedRating(rating);
        return c;
    }

    private String cachedPrefix(LlmJobRequest req) {
        LlmJobRequest.StructuredPrompt sp = req.getStructuredPrompt();
        assertNotNull(sp, "caching-on gap request must carry a structuredPrompt");
        return String.join(" ", sp.getSystemBlocks()) + " CORPUS " + sp.getCachedCorpus();
    }

    // -------------------------------------------------------------------------
    // EvidenceGapAnalyzer
    // -------------------------------------------------------------------------

    @Test
    void evidenceGap_cachingOn_prefixByteEqual_acrossConditions() throws Exception {
        EvidenceGapAnalyzer agent = new EvidenceGapAnalyzer(Mockito.mock(LlmJobService.class));
        setCaching(agent, true);
        List<Atom> atoms = corpus();

        LlmJobRequest a = agent.buildRequest(condition(1L, "Asthma", "6602", 30), atoms, 9L, 8L);
        LlmJobRequest b = agent.buildRequest(condition(2L, "PTSD", "9411", 50), atoms, 9L, 8L);

        assertEquals(cachedPrefix(a), cachedPrefix(b),
                "every gap_evidence call in a run must share the identical cached prefix bytes");
        assertNotEquals(a.getUserMessage(), b.getUserMessage(), "volatile per-condition tail must differ");
        assertFalse(a.getUserMessage().contains("shortness of breath"),
                "atoms must live only in the cached corpus, not the tail");
        assertTrue(a.getStructuredPrompt().getCachedCorpus().contains("shortness of breath"));
    }

    @Test
    void evidenceGap_flagOff_flatShape_atomsInUserMessage() throws Exception {
        EvidenceGapAnalyzer agent = new EvidenceGapAnalyzer(Mockito.mock(LlmJobService.class));
        setCaching(agent, false);
        LlmJobRequest req = agent.buildRequest(condition(1L, "Asthma", "6602", 30), corpus(), 9L, 8L);

        assertNull(req.getStructuredPrompt(), "flag-off must not attach a structuredPrompt");
        assertTrue(req.getUserMessage().contains("Evidence Atoms Already On File (3):"), req.getUserMessage());
        assertTrue(req.getUserMessage().contains("shortness of breath"));
    }

    // -------------------------------------------------------------------------
    // GapValidationAgent
    // -------------------------------------------------------------------------

    private List<Map<String, Object>> gaps() {
        return List.of(Map.of("type", "nexus_letter", "description", "Need a nexus letter"));
    }

    @Test
    void gapValidation_cachingOn_prefixByteEqual_acrossConditions() throws Exception {
        GapValidationAgent agent = new GapValidationAgent(Mockito.mock(LlmJobService.class));
        setCaching(agent, true);
        List<Atom> atoms = corpus();

        LlmJobRequest a = agent.buildRequest(condition(1L, "Asthma", "6602", 30), atoms, gaps(), 9L, 8L);
        LlmJobRequest b = agent.buildRequest(condition(2L, "PTSD", "9411", 50), atoms, gaps(), 9L, 8L);

        assertEquals(cachedPrefix(a), cachedPrefix(b),
                "every gap_validation call in a run must share the identical cached prefix bytes");
        assertNotEquals(a.getUserMessage(), b.getUserMessage(), "volatile per-condition tail must differ");
        assertFalse(a.getUserMessage().contains("shortness of breath"),
                "atoms must live only in the cached corpus, not the tail");
    }

    @Test
    void gapValidation_flagOff_flatShape_atomsInUserMessage() throws Exception {
        GapValidationAgent agent = new GapValidationAgent(Mockito.mock(LlmJobService.class));
        setCaching(agent, false);
        LlmJobRequest req = agent.buildRequest(condition(1L, "Asthma", "6602", 30), corpus(), gaps(), 9L, 8L);

        assertNull(req.getStructuredPrompt(), "flag-off must not attach a structuredPrompt");
        assertTrue(req.getUserMessage().contains("Atoms on file (3):"), req.getUserMessage());
        assertTrue(req.getUserMessage().contains("shortness of breath"));
    }
}
