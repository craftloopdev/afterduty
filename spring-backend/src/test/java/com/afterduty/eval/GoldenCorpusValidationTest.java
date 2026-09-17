package com.afterduty.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Increment 8 corpus validation (spec §8.1) — the test that keeps the
 * hand-authored golden JSON dirs honest. Every case parses; ids are unique; every
 * referenced file exists; every regex compiles (all enforced inside
 * {@link GoldenCaseLoader#loadAll()}). On top of that this test asserts:
 * <ul>
 *   <li>each case's {@code *_by_vasrd} canned keys appear in that case's identify
 *       (or merger) canned output — a rate/gap fan-out must target a DC the case
 *       actually identifies;</li>
 *   <li>the roster covers every required tag (spec §1.5);</li>
 *   <li>no committed case uses an obviously real-looking name (PHI smell, §1.1) —
 *       all synthetic docs carry the "(synthetic)" marker.</li>
 * </ul>
 */
@Tag("eval-offline")
@Tag("regression")
class GoldenCorpusValidationTest {

    private final GoldenCaseLoader loader = new GoldenCaseLoader();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void everyCaseParsesAndValidates() {
        List<GoldenCaseLoader.LoadedCase> cases = loader.loadEntireManifest();
        assertFalse(cases.isEmpty(), "no golden cases loaded");
        assertTrue(cases.size() >= 24, "roster should have at least 24 cases, found " + cases.size());
    }

    @Test
    void byVasrdKeysAreIdentifiedConditions() {
        for (GoldenCaseLoader.LoadedCase lc : loader.loadEntireManifest()) {
            GoldenCase c = lc.caseFile();
            if (c.canned() == null) continue;
            Set<String> identifiedDcs = identifiedDcs(lc);
            assertByVasrdSubset("synthesis_rate", c.canned().synthesisRateByVasrd(), identifiedDcs, c.id());
            assertByVasrdSubset("gap_evidence", c.canned().gapEvidenceByVasrd(), identifiedDcs, c.id());
            assertByVasrdSubset("gap_validation", c.canned().gapValidationByVasrd(), identifiedDcs, c.id());
            assertByVasrdSubset("gap_whatif", c.canned().gapWhatifByVasrd(), identifiedDcs, c.id());
        }
    }

    @Test
    void rosterCoversEveryRequiredTag() {
        Set<String> seen = new HashSet<>();
        for (GoldenCaseLoader.LoadedCase lc : loader.loadEntireManifest()) {
            if (lc.caseFile().tags() != null) seen.addAll(lc.caseFile().tags());
        }
        for (String required : List.of("multi-condition", "bilateral", "presumptive",
                "abstention-expected", "non-medical-docs", "contradictory-evidence",
                "no-conditions-legitimate", "deterministic-rating", "pyramiding", "incremental")) {
            assertTrue(seen.contains(required), "roster is missing required tag: " + required);
        }
    }

    @Test
    void docsCarrySyntheticMarker() {
        for (GoldenCaseLoader.LoadedCase lc : loader.loadEntireManifest()) {
            // PHI rule §1.1 guards the COMMITTED corpus: nothing real on the
            // classpath. Private-tier cases (file: base, never committed) exist
            // precisely to hold real, expert-confirmed content — the marker
            // requirement does not apply to them.
            if (lc.basePath().startsWith("file:")) {
                continue;
            }
            for (GoldenCase.Phase phase : lc.caseFile().phases()) {
                for (GoldenCase.Doc doc : phase.docs()) {
                    String body = loader.readText(lc.basePath(), doc.file());
                    assertTrue(body.toLowerCase().contains("synthetic"),
                            "case " + lc.id() + " doc " + doc.file()
                                    + " must carry an explicit (synthetic) marker (PHI rule §1.1)");
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private Set<String> identifiedDcs(GoldenCaseLoader.LoadedCase lc) {
        Set<String> dcs = new HashSet<>();
        // Prefer the merger output (final conditions); fall back to identify.
        String rel = lc.caseFile().canned().synthesisDuplicateMerger();
        if (rel == null) rel = lc.caseFile().canned().synthesisIdentify();
        if (rel == null) return dcs;
        String text = loader.readText(lc.basePath(), rel);
        try {
            JsonNode node = mapper.readTree(text);
            JsonNode arr = node.isArray() ? node : node.path("standalone_indices"); // merge_groups form falls back to identify
            if (!node.isArray()) {
                // merge_groups form — read DCs from identify instead
                text = loader.readText(lc.basePath(), lc.caseFile().canned().synthesisIdentify());
                node = mapper.readTree(text);
            }
            if (node.isArray()) {
                for (JsonNode cond : node) {
                    JsonNode dc = cond.get("vasrd_code");
                    if (dc != null && !dc.isNull()) dcs.add(dc.asText());
                }
            }
        } catch (Exception e) {
            // identify may be deliberately unparseable (gc-012) — no DCs, that's fine.
        }
        return dcs;
    }

    private void assertByVasrdSubset(String purpose, Map<String, String> byVasrd,
                                     Set<String> identifiedDcs, String caseId) {
        if (byVasrd == null) return;
        for (String dc : byVasrd.keySet()) {
            assertTrue(identifiedDcs.contains(dc),
                    "case " + caseId + " " + purpose + "_by_vasrd targets DC " + dc
                            + " which is not in the identify/merger canned output " + identifiedDcs);
        }
    }
}
