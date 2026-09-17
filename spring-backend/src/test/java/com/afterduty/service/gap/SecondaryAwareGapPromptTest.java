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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Secondary-aware analysis (38 CFR 3.310) — the gap-prompt half.
 *
 * <p>Companion to {@link GapDismissalPromptTest}: the secondary context rides the
 * per-condition VOLATILE tail (never the cache_control-stable prefix) and is EMPTY
 * for a direct claim so non-secondary prompts stay byte-identical. The static
 * carve-out (suppress the direct in-service gap, ask-the-veteran only when the
 * primary's SC status is UNKNOWN, frame nexus as the secondary↔primary link) lives
 * in the SYSTEM_PROMPT prefix.
 */
@Tag("regression")
class SecondaryAwareGapPromptTest {

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
        atoms.add(atom(1L, "diagnosis", "GERD"));
        atoms.add(atom(2L, "symptom", "heartburn"));
        return atoms;
    }

    private IdentifiedCondition directCondition() {
        IdentifiedCondition c = new IdentifiedCondition();
        c.setId(1L);
        c.setName("Right Knee Strain");
        c.setVasrdCode("5260");
        c.setEstimatedRating(10);
        return c;   // secondaryTo == null
    }

    /** GERD secondary to PTSD, with the in-service leg already reframed by reconcileSecondary. */
    private IdentifiedCondition secondaryCondition(String reframedInServiceStatus) {
        IdentifiedCondition c = new IdentifiedCondition();
        c.setId(2L);
        c.setName("GERD (Gastroesophageal Reflux Disease)");
        c.setVasrdCode("7346");
        c.setEstimatedRating(30);
        c.setSecondaryTo("PTSD (Post-Traumatic Stress Disorder)");
        Map<String, Object> leg = new LinkedHashMap<>();
        leg.put("status", reframedInServiceStatus);
        leg.put("evidence", new ArrayList<>(List.of("primary-sc framing")));
        c.setTriadInService(leg);
        return c;
    }

    @Test
    void systemPrompt_carriesSecondaryCarveOut() throws Exception {
        EvidenceGapAnalyzer agent = new EvidenceGapAnalyzer(Mockito.mock(LlmJobService.class));
        setCaching(agent, true);

        LlmJobRequest req = agent.buildRequest(secondaryCondition("MISSING"), corpus(), 9L, 8L);
        String system = String.join(" ", req.getStructuredPrompt().getSystemBlocks());

        assertTrue(system.contains("SECONDARY CONDITIONS (38 CFR 3.310)"),
                "the gap system prompt must carry the secondary carve-out");
        // Normalize whitespace: the text block wraps long instructions across lines.
        String flat = system.replaceAll("\\s+", " ");
        assertTrue(flat.contains("do NOT recommend a direct in-service-event gap"),
                "carve-out must suppress the direct in-service gap for secondaries");
        assertTrue(flat.contains("CAUSED or AGGRAVATED"),
                "carve-out must frame the nexus gap as causation OR aggravation");
        assertTrue(flat.toLowerCase().contains("confirm your"),
                "carve-out must instruct an ask-the-veteran confirmation gap when the primary is UNKNOWN");
    }

    @Test
    void secondaryCondition_unknownPrimary_tailShowsPrimaryAndUnknownStatus() throws Exception {
        EvidenceGapAnalyzer agent = new EvidenceGapAnalyzer(Mockito.mock(LlmJobService.class));
        setCaching(agent, true);

        // MISSING in-service leg (reconcileSecondary's UNKNOWN output).
        LlmJobRequest req = agent.buildRequest(secondaryCondition("MISSING"), corpus(), 9L, 8L);
        String tail = req.getUserMessage();

        assertTrue(tail.contains("Secondary To (38 CFR 3.310"), "tail names the primary");
        assertTrue(tail.contains("PTSD (Post-Traumatic Stress Disorder)"));
        assertTrue(tail.contains("Primary Service-Connection Status: UNKNOWN"),
                "a MISSING/WEAK reframed leg surfaces as UNKNOWN so the model asks the veteran");
        assertTrue(tail.toLowerCase().contains("ask the veteran"));
    }

    @Test
    void secondaryCondition_strongLeg_surfacesKnownStatus() throws Exception {
        EvidenceGapAnalyzer agent = new EvidenceGapAnalyzer(Mockito.mock(LlmJobService.class));
        setCaching(agent, true);

        LlmJobRequest req = agent.buildRequest(secondaryCondition("STRONG"), corpus(), 9L, 8L);
        assertTrue(req.getUserMessage().contains("Primary Service-Connection Status: KNOWN"),
                "a STRONG reframed in-service leg surfaces as KNOWN (no confirm-primary ask)");
    }

    @Test
    void directCondition_promptByteIdenticalToPreSecondaryShape() throws Exception {
        // A direct (non-secondary) condition must render exactly as before: the
        // secondary context is empty, so the "Presumptive Basis" → "VA Knowledge-Base"
        // block is unchanged. We assert the secondary lines are simply absent.
        EvidenceGapAnalyzer agent = new EvidenceGapAnalyzer(Mockito.mock(LlmJobService.class));
        setCaching(agent, true);

        LlmJobRequest req = agent.buildRequest(directCondition(), corpus(), 9L, 8L);
        String tail = req.getUserMessage();
        assertFalse(tail.contains("Secondary To"),
                "a direct claim must not render any secondary context");
        assertFalse(tail.contains("Primary Service-Connection Status"),
                "a direct claim must not render a primary-SC status line");
        // The original adjacency is preserved: the blank line between the presumptive
        // block and the KB reference stays a single blank line.
        assertTrue(tail.contains("Presumptive Basis: n/a\n\nVA Knowledge-Base Reference:"),
                "non-secondary layout must be byte-identical to the pre-feature shape");
    }

    @Test
    void flagOffFlatShape_secondaryContextStillInjected() throws Exception {
        EvidenceGapAnalyzer agent = new EvidenceGapAnalyzer(Mockito.mock(LlmJobService.class));
        setCaching(agent, false);

        LlmJobRequest req = agent.buildRequest(secondaryCondition("MODERATE"), corpus(), 9L, 8L);
        assertNull(req.getStructuredPrompt(), "flag-off must not attach a structuredPrompt");
        String user = req.getUserMessage();
        assertTrue(user.contains("Secondary To (38 CFR 3.310"));
        assertTrue(user.contains("Primary Service-Connection Status: LIKELY"),
                "a MODERATE reframed leg surfaces as LIKELY");
    }
}
