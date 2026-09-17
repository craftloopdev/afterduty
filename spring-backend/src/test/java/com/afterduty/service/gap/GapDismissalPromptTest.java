package com.afterduty.service.gap;

import com.afterduty.model.Atom;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.model.UserGapState;
import com.afterduty.service.llm.LlmJobRequest;
import com.afterduty.service.llm.LlmJobService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-6 — do-not-re-propose injection in {@link EvidenceGapAnalyzer}.
 *
 * The dismissed block is deterministic text (string template only), rides the
 * VOLATILE userMessage tail, and must never touch the cache_control-stable
 * prefix (system blocks + atom corpus) — companion to GapPrefixStabilityTest.
 */
@Tag("regression")
class GapDismissalPromptTest {

    private static final String HEADER =
            "The veteran has said these do not apply — do not re-propose them:";

    private static void setCaching(EvidenceGapAnalyzer agent, boolean caching) throws Exception {
        Field f = agent.getClass().getDeclaredField("promptCachingEnabled");
        f.setAccessible(true);
        f.set(agent, caching);
    }

    private Atom atom(Long id, String type, String value) {
        Atom a = new Atom();
        a.setId(id);
        a.setEvidenceId(10L);
        a.setType(type);
        a.setValue(value);
        a.setSource("doc-10");
        a.setConfidence(0.85);
        return a;
    }

    private List<Atom> corpus() {
        List<Atom> atoms = new ArrayList<>();
        atoms.add(atom(1L, "diagnosis", "asthma"));
        atoms.add(atom(2L, "symptom", "shortness of breath"));
        return atoms;
    }

    private IdentifiedCondition condition() {
        IdentifiedCondition c = new IdentifiedCondition();
        c.setId(1L);
        c.setName("Asthma");
        c.setVasrdCode("6602");
        c.setEstimatedRating(30);
        return c;
    }

    private UserGapState dismissed(String type, String leg) {
        return new UserGapState(9L, "fp-1", type, leg, UserGapStateService.STATUS_DISMISSED);
    }

    private String cachedPrefix(LlmJobRequest req) {
        LlmJobRequest.StructuredPrompt sp = req.getStructuredPrompt();
        assertNotNull(sp, "caching-on gap request must carry a structuredPrompt");
        return String.join(" ", sp.getSystemBlocks()) + " CORPUS " + sp.getCachedCorpus();
    }

    @Test
    void cachingOn_dismissedBlockOnVolatileTail_prefixBytesUntouched() throws Exception {
        EvidenceGapAnalyzer agent = new EvidenceGapAnalyzer(Mockito.mock(LlmJobService.class));
        setCaching(agent, true);
        List<Atom> atoms = corpus();

        LlmJobRequest without = agent.buildRequest(condition(), atoms, 9L, 8L);
        LlmJobRequest with = agent.buildRequest(condition(), atoms, 9L, 8L,
                List.of(dismissed("nexus_letter", "nexus"), dismissed("buddy_statement", null)));

        // The block lands on the tail, with (type, leg) rendered per entry.
        assertTrue(with.getUserMessage().contains(HEADER));
        assertTrue(with.getUserMessage().contains("- nexus_letter (triad leg: nexus)"));
        assertTrue(with.getUserMessage().contains("- buddy_statement\n"),
                "a null-leg entry renders without a leg suffix");

        // Cache safety: the stable prefix is byte-identical with and without
        // dismissals — injecting the block can never bust the prompt cache.
        assertEquals(cachedPrefix(without), cachedPrefix(with),
                "dismissed context must not change the cached prefix bytes");
        assertFalse(cachedPrefix(with).contains(HEADER),
                "dismissed context must never appear in the cached prefix");
    }

    @Test
    void emptyDismissals_byteIdenticalToLegacyOverload() throws Exception {
        EvidenceGapAnalyzer agent = new EvidenceGapAnalyzer(Mockito.mock(LlmJobService.class));
        setCaching(agent, true);
        List<Atom> atoms = corpus();

        LlmJobRequest legacy = agent.buildRequest(condition(), atoms, 9L, 8L);
        LlmJobRequest empty = agent.buildRequest(condition(), atoms, 9L, 8L, List.of());

        assertEquals(legacy.getUserMessage(), empty.getUserMessage(),
                "no dismissals → prompt byte-identical to the 4-arg overload");
        assertFalse(legacy.getUserMessage().contains(HEADER));
    }

    @Test
    void flagOff_flatShape_blockStillInjected() throws Exception {
        EvidenceGapAnalyzer agent = new EvidenceGapAnalyzer(Mockito.mock(LlmJobService.class));
        setCaching(agent, false);

        LlmJobRequest req = agent.buildRequest(condition(), corpus(), 9L, 8L,
                List.of(dismissed("treatment_record", "severity")));

        assertNull(req.getStructuredPrompt(), "flag-off must not attach a structuredPrompt");
        assertTrue(req.getUserMessage().contains(HEADER));
        assertTrue(req.getUserMessage().contains("- treatment_record (triad leg: severity)"));
    }

    @Test
    void rendering_isDeterministicallySorted() throws Exception {
        EvidenceGapAnalyzer agent = new EvidenceGapAnalyzer(Mockito.mock(LlmJobService.class));
        setCaching(agent, true);
        List<Atom> atoms = corpus();

        // Same entries, different input order → identical rendered prompts.
        LlmJobRequest a = agent.buildRequest(condition(), atoms, 9L, 8L,
                List.of(dismissed("nexus_letter", "nexus"), dismissed("buddy_statement", null)));
        LlmJobRequest b = agent.buildRequest(condition(), atoms, 9L, 8L,
                List.of(dismissed("buddy_statement", null), dismissed("nexus_letter", "nexus")));

        assertEquals(a.getUserMessage(), b.getUserMessage(),
                "dismissed entries must render in a stable (type, leg) sort order");
        assertTrue(a.getUserMessage().indexOf("- buddy_statement") <
                        a.getUserMessage().indexOf("- nexus_letter"),
                "entries sort by gap type");
    }
}
