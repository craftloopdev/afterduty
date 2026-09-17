package com.afterduty.service.synthesis;

import com.afterduty.model.Atom;
import com.afterduty.model.IdentifiedCondition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mission 5b — pure unit tests for {@link ConditionGenerationService} fingerprints
 * and carry-forward classification. No Spring context: the repository dependency is
 * only touched by the supersede/activate methods, which these tests don't exercise,
 * so a null repo is safe here (those paths are covered by the integration test).
 *
 * The headline case the judges flagged: two same-DC conditions that differ ONLY by
 * laterality (bilateral / left / right / dual-site) MUST get distinct identity
 * fingerprints, or a re-analysis would mis-merge them and corrupt the veteran's
 * history.
 */
class ConditionGenerationServiceTest {

    private final ConditionGenerationService svc = new ConditionGenerationService(null);

    private static IdentifiedCondition cond(String name, String vasrd, String bodySystem) {
        IdentifiedCondition c = new IdentifiedCondition();
        c.setName(name);
        c.setVasrdCode(vasrd);
        c.setBodySystem(bodySystem);
        return c;
    }

    private static Atom atom(Long evidenceId, String type, String value, String source) {
        return Atom.builder().claimId(1L).evidenceId(evidenceId)
                .type(type).value(value).source(source).build();
    }

    private static Atom atom(Long evidenceId, String type, String value, String source, double confidence) {
        return Atom.builder().claimId(1L).evidenceId(evidenceId)
                .type(type).value(value).source(source).confidence(confidence).build();
    }

    // -------------------------------------------------------------------------
    // Laterality — the bilateral mis-merge risk
    // -------------------------------------------------------------------------

    @Test
    void bilateral_left_right_sameDC_getDistinctIdentities() {
        // Knee strain DC 5260, three laterality variants. All MUST differ.
        String bilateral = svc.computeIdentityFingerprint(
                cond("Bilateral knee strain", "5260", "musculoskeletal"));
        String left = svc.computeIdentityFingerprint(
                cond("Left knee strain", "5260", "musculoskeletal"));
        String right = svc.computeIdentityFingerprint(
                cond("Right knee strain", "5260", "musculoskeletal"));

        assertNotEquals(bilateral, left, "bilateral vs left must not collapse");
        assertNotEquals(bilateral, right, "bilateral vs right must not collapse");
        assertNotEquals(left, right, "left vs right must not collapse");
    }

    @Test
    void dualSite_distinctFromBilateralAndSingleSide() {
        String dual = svc.computeIdentityFingerprint(
                cond("Left and right shoulder arthritis", "5003", "musculoskeletal"));
        String bilateral = svc.computeIdentityFingerprint(
                cond("Bilateral shoulder arthritis", "5003", "musculoskeletal"));
        String left = svc.computeIdentityFingerprint(
                cond("Left shoulder arthritis", "5003", "musculoskeletal"));
        assertNotEquals(dual, bilateral);
        assertNotEquals(dual, left);
    }

    @Test
    void sameConditionAcrossGenerations_getsStableIdentity() {
        // Same DC + same name re-identified in a later run keeps the SAME identity,
        // so the old generation can be linked to its replacement.
        String gen1 = svc.computeIdentityFingerprint(cond("PTSD", "9411", "mental"));
        String gen2 = svc.computeIdentityFingerprint(cond("PTSD", "9411", "mental"));
        assertEquals(gen1, gen2, "same condition must keep a stable identity across runs");
    }

    @Test
    void secondaryConnectionTheory_distinctFromDirect() {
        String direct = svc.computeIdentityFingerprint(cond("Sleep apnea", "6847", "respiratory"));
        String secondary = svc.computeIdentityFingerprint(
                cond("Sleep apnea (secondary to PTSD)", "6847", "respiratory"));
        assertNotEquals(direct, secondary,
                "a secondary-connection condition is a distinct claim from the direct one");
    }

    @Test
    void differentDiagnosticCodes_distinctIdentities() {
        String a = svc.computeIdentityFingerprint(cond("PTSD", "9411", "mental"));
        String b = svc.computeIdentityFingerprint(cond("TBI", "8045", "neurological"));
        assertNotEquals(a, b);
    }

    @Test
    void codelessConditions_distinguishedByName() {
        // Two DC-less conditions must NOT share the "novc" identity — otherwise
        // they'd wrongly carry-forward into each other.
        String a = svc.computeIdentityFingerprint(cond("Chronic fatigue", null, "general"));
        String b = svc.computeIdentityFingerprint(cond("Migraines", null, "neurological"));
        assertNotEquals(a, b);
    }

    @Test
    void parseLaterality_tokens() {
        assertEquals("bilateral", svc.parseLaterality("Bilateral hearing loss"));
        assertEquals("bilateral", svc.parseLaterality("Hearing loss, both ears"));
        assertEquals("left", svc.parseLaterality("Left knee strain"));
        assertEquals("right", svc.parseLaterality("Right shoulder impingement"));
        assertEquals("dual:left+right", svc.parseLaterality("Left and right hip arthritis"));
        assertEquals("none", svc.parseLaterality("PTSD"));
    }

    // -------------------------------------------------------------------------
    // Evidence fingerprint
    // -------------------------------------------------------------------------

    @Test
    void evidenceFingerprint_orderIndependent() {
        Atom a1 = atom(10L, "diagnosis", "PTSD established", "VA exam 2023");
        Atom a2 = atom(11L, "medication", "sertraline 50mg", "pharmacy 2023");
        String fpA = svc.computeEvidenceFingerprint(List.of(a1, a2), "model-x");
        String fpB = svc.computeEvidenceFingerprint(List.of(a2, a1), "model-x");
        assertEquals(fpA, fpB, "atom order must not change the evidence fingerprint");
    }

    @Test
    void evidenceFingerprint_identicalContentDifferentDbIds_matches() {
        // A re-extraction mints NEW db ids for byte-identical facts; the fingerprint
        // is over (evidence_id, type, value, source), NOT the db id, so a no-op
        // re-upload yields the SAME fingerprint (⇒ no-new-facts / clean).
        Atom original = atom(10L, "diagnosis", "PTSD established", "VA exam 2023");
        original.setId(1L);
        Atom reextracted = atom(10L, "diagnosis", "PTSD established", "VA exam 2023");
        reextracted.setId(999L);
        assertEquals(
                svc.computeEvidenceFingerprint(List.of(original), "m"),
                svc.computeEvidenceFingerprint(List.of(reextracted), "m"),
                "byte-identical re-extracted atoms must produce the same evidence fingerprint");
    }

    @Test
    void evidenceFingerprint_newFactChangesHash() {
        Atom a1 = atom(10L, "diagnosis", "PTSD established", "VA exam 2023");
        String before = svc.computeEvidenceFingerprint(List.of(a1), "m");
        Atom a2 = atom(12L, "diagnosis", "GERD established", "GI clinic 2024");
        String after = svc.computeEvidenceFingerprint(List.of(a1, a2), "m");
        assertNotEquals(before, after, "a new fact must change the evidence fingerprint");
    }

    @Test
    void evidenceFingerprint_modelSwapChangesHash() {
        Atom a1 = atom(10L, "diagnosis", "PTSD established", "VA exam 2023");
        assertNotEquals(
                svc.computeEvidenceFingerprint(List.of(a1), "model-a"),
                svc.computeEvidenceFingerprint(List.of(a1), "model-b"),
                "a routed-model swap must invalidate carry-forward");
    }

    @Test
    void evidenceFingerprint_confidenceChangeChangesHash() {
        // The rating prompt RENDERS each atom's confidence (RatingAgent.formatAtoms:
        // "... confidence: %.2f"). A re-extraction that yields byte-identical
        // type/value/source but a DIFFERENT model-assigned confidence genuinely
        // changes the text the rating model sees, so the evidence fingerprint MUST
        // flip — otherwise the no-new-facts short-circuit would falsely skip the
        // re-run and the condition would carry a stale rating forward.
        Atom lowConf = atom(10L, "diagnosis", "PTSD established", "VA exam 2023", 0.40);
        Atom highConf = atom(10L, "diagnosis", "PTSD established", "VA exam 2023", 0.95);
        assertNotEquals(
                svc.computeEvidenceFingerprint(List.of(lowConf), "m"),
                svc.computeEvidenceFingerprint(List.of(highConf), "m"),
                "a confidence-only change on an otherwise-identical atom must change the evidence fingerprint");
    }

    @Test
    void evidenceFingerprint_subRenderingConfidenceJitter_doesNotChangeHash() {
        // The prompt renders confidence at %.2f, so two confidences that round to the
        // same two decimals are textually identical to the model — they must NOT
        // needlessly invalidate carry-forward.
        // Both round to "0.88" at %.2f, so the rating model sees identical text.
        Atom a = atom(10L, "diagnosis", "PTSD established", "VA exam 2023", 0.8841);
        Atom b = atom(10L, "diagnosis", "PTSD established", "VA exam 2023", 0.8849);
        assertEquals(
                svc.computeEvidenceFingerprint(List.of(a), "m"),
                svc.computeEvidenceFingerprint(List.of(b), "m"),
                "confidences that render to the same %.2f text must not change the fingerprint");
    }

    @Test
    void classify_dirtyWhenOnlyConfidenceChanged() {
        // End-to-end of the major finding: identity matches, but the prior generation
        // was rated on a different atom-confidence corpus, so the run's evidence
        // fingerprint differs from the prior's → the condition is DIRTY (re-rated),
        // NOT a stale carry-forward.
        Atom priorAtom = atom(10L, "diagnosis", "PTSD established", "VA exam 2023", 0.40);
        Atom currentAtom = atom(10L, "diagnosis", "PTSD established", "VA exam 2023", 0.95);

        IdentifiedCondition prior = cond("PTSD", "9411", "mental");
        prior.setIdentityFingerprint(svc.computeIdentityFingerprint(prior));
        prior.setEvidenceFingerprint(svc.computeEvidenceFingerprint(List.of(priorAtom), "m"));
        prior.setEstimatedRating(70);

        IdentifiedCondition fresh = cond("PTSD", "9411", "mental");
        fresh.setIdentityFingerprint(svc.computeIdentityFingerprint(fresh));
        fresh.setEvidenceFingerprint(svc.computeEvidenceFingerprint(List.of(currentAtom), "m"));

        var decision = svc.classify(fresh, svc.indexPriorByIdentity(List.of(prior)));
        assertFalse(decision.clean(),
                "a confidence-only change (which the rating prompt renders) must make the condition DIRTY, not carry-forward stale");
    }

    // -------------------------------------------------------------------------
    // Carry-forward classification
    // -------------------------------------------------------------------------

    @Test
    void classify_cleanWhenIdentityAndEvidenceMatch() {
        IdentifiedCondition prior = cond("PTSD", "9411", "mental");
        prior.setIdentityFingerprint(svc.computeIdentityFingerprint(prior));
        prior.setEvidenceFingerprint("EV1");
        prior.setEstimatedRating(70);

        IdentifiedCondition fresh = cond("PTSD", "9411", "mental");
        fresh.setIdentityFingerprint(svc.computeIdentityFingerprint(fresh));
        fresh.setEvidenceFingerprint("EV1");

        var decision = svc.classify(fresh, svc.indexPriorByIdentity(List.of(prior)));
        assertTrue(decision.clean(), "identity + evidence match ⇒ CLEAN");
        assertSame(prior, decision.priorMatch());
    }

    @Test
    void classify_dirtyWhenEvidenceChanged() {
        IdentifiedCondition prior = cond("PTSD", "9411", "mental");
        prior.setIdentityFingerprint(svc.computeIdentityFingerprint(prior));
        prior.setEvidenceFingerprint("EV1");

        IdentifiedCondition fresh = cond("PTSD", "9411", "mental");
        fresh.setIdentityFingerprint(svc.computeIdentityFingerprint(fresh));
        fresh.setEvidenceFingerprint("EV2"); // evidence changed

        var decision = svc.classify(fresh, svc.indexPriorByIdentity(List.of(prior)));
        assertFalse(decision.clean(), "changed evidence ⇒ DIRTY");
    }

    @Test
    void classify_dirtyWhenNoPriorMatch() {
        IdentifiedCondition prior = cond("PTSD", "9411", "mental");
        prior.setIdentityFingerprint(svc.computeIdentityFingerprint(prior));
        prior.setEvidenceFingerprint("EV1");

        IdentifiedCondition freshNew = cond("Tinnitus", "6260", "ear");
        freshNew.setIdentityFingerprint(svc.computeIdentityFingerprint(freshNew));
        freshNew.setEvidenceFingerprint("EV1");

        var decision = svc.classify(freshNew, svc.indexPriorByIdentity(List.of(prior)));
        assertFalse(decision.clean(), "a brand-new condition (no prior identity) ⇒ DIRTY");
    }

    @Test
    void carryForward_copiesOutputsAndNormalizesGaps() {
        IdentifiedCondition prior = cond("PTSD", "9411", "mental");
        prior.setEstimatedRating(70);
        prior.setRatingRationale("rationale");
        prior.setConfidence(0.9);
        prior.setGaps(null); // prior genuinely had no gaps
        prior.setWhatIfScenarios(List.of(Map.of("k", "v")));
        prior.setPyramidGroup("mental_health");

        IdentifiedCondition fresh = cond("PTSD", "9411", "mental");
        svc.carryForward(fresh, prior);

        assertEquals(70, fresh.getEstimatedRating());
        assertEquals("rationale", fresh.getRatingRationale());
        assertEquals(0.9, fresh.getConfidence());
        assertNotNull(fresh.getGaps(), "gaps normalized to non-null so the gap stage marks it CLEAN");
        assertTrue(fresh.getGaps().isEmpty());
        assertFalse(svc.needsGapAnalysis(fresh), "carried-forward condition must not need gap analysis");
        assertEquals("mental_health", fresh.getPyramidGroup());
    }

    @Test
    void needsGapAnalysis_trueForFreshDirtyCondition() {
        IdentifiedCondition fresh = cond("PTSD", "9411", "mental");
        assertNull(fresh.getGaps());
        assertTrue(svc.needsGapAnalysis(fresh), "a freshly rated condition (null gaps) is DIRTY for gaps");
    }

    @Test
    void carryForward_reanchorsWhatIfScenarioConditionIdToFreshCondition() {
        // A carried-forward what-if scenario must reference the NEW active condition
        // id, not the prior (now-superseded) one — these are served raw to clients.
        IdentifiedCondition prior = cond("PTSD", "9411", "mental");
        prior.setId(11L); // prior generation's id
        prior.setEstimatedRating(70);
        Map<String, Object> priorScenario = new java.util.HashMap<>();
        priorScenario.put("scenario", "obtain DBQ");
        priorScenario.put("conditionId", 11L); // baked-in PRIOR condition id
        prior.setWhatIfScenarios(List.of(priorScenario));

        IdentifiedCondition fresh = cond("PTSD", "9411", "mental");
        fresh.setId(42L); // new active generation's id (set on persist before carryForward)

        svc.carryForward(fresh, prior);

        assertNotNull(fresh.getWhatIfScenarios());
        assertEquals(1, fresh.getWhatIfScenarios().size());
        assertEquals(42L, fresh.getWhatIfScenarios().get(0).get("conditionId"),
                "carried-forward what-if scenario's nested conditionId must equal the new active condition id");
        assertEquals("obtain DBQ", fresh.getWhatIfScenarios().get(0).get("scenario"),
                "other scenario fields are preserved verbatim");
        // The prior row's scenario map must NOT be mutated (no aliasing into persisted JSON).
        assertEquals(11L, priorScenario.get("conditionId"),
                "re-anchoring must copy, not mutate the prior generation's scenario in place");
    }

    @Test
    void carryForward_reanchorsSnakeCaseConditionIdKey() {
        IdentifiedCondition prior = cond("PTSD", "9411", "mental");
        prior.setId(11L);
        Map<String, Object> priorScenario = new java.util.HashMap<>();
        priorScenario.put("condition_id", 11L); // snake_case variant
        prior.setWhatIfScenarios(List.of(priorScenario));

        IdentifiedCondition fresh = cond("PTSD", "9411", "mental");
        fresh.setId(42L);
        svc.carryForward(fresh, prior);

        assertEquals(42L, fresh.getWhatIfScenarios().get(0).get("condition_id"),
                "snake_case condition_id is re-anchored too");
    }

    @Test
    void carryForward_whatIfWithoutConditionId_isUnchangedAndNonNull() {
        // No embedded condition id → nothing to re-anchor; the scenario is copied
        // verbatim and the list is non-null (the clean/dirty signal).
        IdentifiedCondition prior = cond("PTSD", "9411", "mental");
        prior.setId(11L);
        prior.setWhatIfScenarios(List.of(Map.of("scenario", "no id here")));

        IdentifiedCondition fresh = cond("PTSD", "9411", "mental");
        fresh.setId(42L);
        svc.carryForward(fresh, prior);

        assertNotNull(fresh.getWhatIfScenarios());
        assertEquals(1, fresh.getWhatIfScenarios().size());
        assertEquals("no id here", fresh.getWhatIfScenarios().get(0).get("scenario"));
        assertFalse(fresh.getWhatIfScenarios().get(0).containsKey("conditionId"),
                "must not inject a spurious conditionId where the payload had none");
    }

    // -------------------------------------------------------------------------
    // Item B2 — attribution at identify (parse + persist)
    // -------------------------------------------------------------------------

    private static Atom liveAtom(long id, Long evidenceId, String type, String value, String source) {
        Atom a = atom(evidenceId, type, value, source);
        a.setId(id);
        return a;
    }

    @Test
    void parseSupportingAtomIds_toleratesModelShapes() {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("supporting_atom_ids", List.of(12, "34", "atom 56", "A78", "no-digits"));
        assertEquals(List.of(12L, 34L, 56L, 78L),
                ConditionGenerationService.parseSupportingAtomIds(m),
                "integers, numeric strings, and 'atom N'/'A<N>' labels all parse; garbage is dropped");

        assertTrue(ConditionGenerationService.parseSupportingAtomIds(Map.of()).isEmpty(),
                "absent field ⇒ empty");
        assertTrue(ConditionGenerationService.parseSupportingAtomIds(
                        Map.of("supporting_atom_ids", "12")).isEmpty(),
                "non-list shape ⇒ empty, never a crash");
    }

    @Test
    void buildAttributionIndex_unionsDuplicatesSharingIdentity() {
        // "PTSD" and "Post-Traumatic Stress Disorder" share DC 9411 + body system
        // (identity anchors on the code, not the free-text name), so the merger will
        // absorb one into the other — the index must carry the UNION of their atoms
        // so the merged row's scoped fingerprint covers both citation sets.
        Map<String, Object> a = Map.of(
                "name", "PTSD", "vasrd_code", "9411", "body_system", "mental",
                "supporting_atom_ids", List.of(1, 2));
        Map<String, Object> b = Map.of(
                "name", "Post-Traumatic Stress Disorder", "vasrd_code", "9411", "body_system", "mental",
                "supporting_atom_ids", List.of(2, 3));

        Map<String, java.util.Set<Long>> index = svc.buildAttributionIndex(List.of(a, b));

        String fp = svc.computeIdentityFingerprint(cond("PTSD", "9411", "mental"));
        assertEquals(java.util.Set.of(1L, 2L, 3L), index.get(fp),
                "duplicates sharing an identity fingerprint contribute the union of their atom ids");
    }

    @Test
    void applyAttribution_persistsFilteredUnion_nullWhenNothingUsable() {
        List<Atom> live = List.of(
                liveAtom(1L, 10L, "diagnosis", "PTSD established", "VA exam"),
                liveAtom(2L, 10L, "symptom", "nightmares", "VA exam"),
                liveAtom(3L, 11L, "symptom", "tinnitus", "audiology"));

        IdentifiedCondition cond = cond("PTSD", "9411", "mental");
        Map<String, Object> condMap = Map.of(
                "name", "PTSD", "vasrd_code", "9411", "body_system", "mental",
                "supporting_atom_ids", List.of(1, 99)); // 99 is hallucinated
        Map<String, java.util.Set<Long>> index = Map.of(
                svc.computeIdentityFingerprint(cond), java.util.Set.of(2L));

        svc.applyAttribution(cond, condMap, index, live);
        assertEquals(List.of(1L, 2L), cond.getSupportingAtomIds(),
                "condMap ids ∪ index ids, filtered to atoms that actually exist (99 dropped)");

        // Nothing usable (all hallucinated) ⇒ null — the attribution-missing marker.
        IdentifiedCondition unattributed = cond("Tinnitus", "6260", "ear");
        svc.applyAttribution(unattributed,
                Map.of("supporting_atom_ids", List.of(777)), Map.of(), live);
        assertNull(unattributed.getSupportingAtomIds(),
                "hallucinated-only attribution persists as null (valve (a) marker)");
    }

    // -------------------------------------------------------------------------
    // Item B2 — scoped evidence fingerprints (flag + valves)
    // -------------------------------------------------------------------------

    @Test
    void stampEvidenceFingerprints_flagOff_isCorpusWideByteForByte() {
        List<Atom> live = List.of(
                liveAtom(1L, 10L, "diagnosis", "PTSD established", "VA exam"),
                liveAtom(2L, 11L, "symptom", "tinnitus", "audiology"));
        String corpusFp = svc.computeEvidenceFingerprint(live, "m");

        IdentifiedCondition cond = cond("PTSD", "9411", "mental");
        cond.setSupportingAtomIds(List.of(1L)); // attribution present but flag OFF
        svc.stampEvidenceFingerprints(cond, live, "m", corpusFp);

        assertEquals(corpusFp, cond.getEvidenceFingerprint(),
                "flag OFF ⇒ evidence fingerprint is the corpus-wide hash, byte-for-byte today's value");
        assertEquals(corpusFp, cond.getCorpusFingerprint(),
                "corpus fingerprint is stamped in both flag states (the scheduler's short-circuit column)");
    }

    @Test
    void stampEvidenceFingerprints_flagOn_scopesToAttributedAtomsOnly() {
        svc.setPerConditionFingerprint(true);
        Atom ptsdAtom = liveAtom(1L, 10L, "diagnosis", "PTSD established", "VA exam");
        Atom tinnitusAtom = liveAtom(2L, 11L, "symptom", "tinnitus", "audiology");
        List<Atom> corpus1 = List.of(ptsdAtom, tinnitusAtom);

        IdentifiedCondition ptsd = cond("PTSD", "9411", "mental");
        ptsd.setSupportingAtomIds(List.of(1L));
        svc.stampEvidenceFingerprints(ptsd, corpus1, "m", svc.computeEvidenceFingerprint(corpus1, "m"));

        assertEquals(svc.computeEvidenceFingerprint(List.of(ptsdAtom), "m"),
                ptsd.getEvidenceFingerprint(),
                "flag ON ⇒ evidence fingerprint hashes ONLY the condition's attributed atoms");

        // A NEW unrelated atom changes the corpus but NOT this condition's scope:
        // the scoped fingerprint is unchanged (⇒ carry-forward-eligible), while the
        // corpus fingerprint moves (⇒ the run itself is correctly triggered).
        Atom unrelated = liveAtom(3L, 12L, "diagnosis", "GERD established", "GI clinic");
        List<Atom> corpus2 = List.of(ptsdAtom, tinnitusAtom, unrelated);
        IdentifiedCondition ptsdRun2 = cond("PTSD", "9411", "mental");
        ptsdRun2.setSupportingAtomIds(List.of(1L));
        svc.stampEvidenceFingerprints(ptsdRun2, corpus2, "m", svc.computeEvidenceFingerprint(corpus2, "m"));

        assertEquals(ptsd.getEvidenceFingerprint(), ptsdRun2.getEvidenceFingerprint(),
                "an unrelated new fact must NOT change an attributed condition's scoped fingerprint");
        assertNotEquals(ptsd.getCorpusFingerprint(), ptsdRun2.getCorpusFingerprint(),
                "the corpus fingerprint still moves (no-new-facts detection stays corpus-wide)");

        // An attributed atom set that grew (the new fact IS cited) ⇒ scoped hash moves.
        IdentifiedCondition ptsdGrew = cond("PTSD", "9411", "mental");
        ptsdGrew.setSupportingAtomIds(List.of(1L, 3L));
        svc.stampEvidenceFingerprints(ptsdGrew, corpus2, "m", svc.computeEvidenceFingerprint(corpus2, "m"));
        assertNotEquals(ptsd.getEvidenceFingerprint(), ptsdGrew.getEvidenceFingerprint(),
                "a newly attributed fact must change the scoped fingerprint (⇒ DIRTY)");
    }

    @Test
    void stampEvidenceFingerprints_flagOn_scopedHashStableAcrossReextractionIds() {
        // A re-extraction mints NEW atom db ids for byte-identical facts; the scoped
        // hash is over the atoms' natural keys, so it must not move.
        svc.setPerConditionFingerprint(true);
        Atom run1Atom = liveAtom(1L, 10L, "diagnosis", "PTSD established", "VA exam");
        Atom run2Atom = liveAtom(999L, 10L, "diagnosis", "PTSD established", "VA exam");

        IdentifiedCondition run1 = cond("PTSD", "9411", "mental");
        run1.setSupportingAtomIds(List.of(1L));
        svc.stampEvidenceFingerprints(run1, List.of(run1Atom), "m", "corpus-1");

        IdentifiedCondition run2 = cond("PTSD", "9411", "mental");
        run2.setSupportingAtomIds(List.of(999L));
        svc.stampEvidenceFingerprints(run2, List.of(run2Atom), "m", "corpus-2");

        assertEquals(run1.getEvidenceFingerprint(), run2.getEvidenceFingerprint(),
                "re-extracted byte-identical atoms (new db ids) keep the scoped fingerprint stable");
    }

    @Test
    void stampEvidenceFingerprints_flagOn_unattributedFallsBackToCorpus() {
        // Safety valve (a): no attribution ⇒ corpus-wide hash, so the condition
        // dirties whenever ANY atom changes (conservative superset of a
        // body-system hash — atoms carry no body-system tag).
        svc.setPerConditionFingerprint(true);
        List<Atom> live = List.of(liveAtom(1L, 10L, "diagnosis", "PTSD established", "VA exam"));
        String corpusFp = svc.computeEvidenceFingerprint(live, "m");

        IdentifiedCondition unattributed = cond("PTSD", "9411", "mental");
        unattributed.setSupportingAtomIds(null);
        svc.stampEvidenceFingerprints(unattributed, live, "m", corpusFp);
        assertEquals(corpusFp, unattributed.getEvidenceFingerprint(),
                "valve (a): attribution missing ⇒ corpus-wide fingerprint");

        IdentifiedCondition unresolvable = cond("PTSD", "9411", "mental");
        unresolvable.setSupportingAtomIds(List.of(777L)); // resolves to no live atom
        svc.stampEvidenceFingerprints(unresolvable, live, "m", corpusFp);
        assertEquals(corpusFp, unresolvable.getEvidenceFingerprint(),
                "valve (a) belt-and-braces: unresolvable ids ⇒ corpus-wide fingerprint");
    }

    // -------------------------------------------------------------------------
    // Item B2 — classify valves (b) new-condition, (c) 30-day full refresh
    // -------------------------------------------------------------------------

    @Test
    void classify_flagOn_thirtyDayValve_forcesStaleCleanConditionDirty() {
        svc.setPerConditionFingerprint(true);

        IdentifiedCondition prior = cond("PTSD", "9411", "mental");
        prior.setIdentityFingerprint(svc.computeIdentityFingerprint(prior));
        prior.setEvidenceFingerprint("EV1");
        prior.setLastFullRunAt(java.time.Instant.now().minus(31, java.time.temporal.ChronoUnit.DAYS));

        IdentifiedCondition fresh = freshLike(prior, "EV1"); // fingerprint-clean

        var decision = svc.classify(fresh, svc.indexPriorByIdentity(List.of(prior)));
        assertFalse(decision.clean(),
                "valve (c): a fingerprint-clean condition whose last full run is >30 days old goes DIRTY");
        assertNotNull(fresh.getLastFullRunAt(), "the dirty decision stamps the fresh row's full-run time");
    }

    @Test
    void classify_flagOn_nullLastFullRun_isTreatedStale_andRecentIsClean() {
        svc.setPerConditionFingerprint(true);

        IdentifiedCondition legacyPrior = cond("PTSD", "9411", "mental");
        legacyPrior.setIdentityFingerprint(svc.computeIdentityFingerprint(legacyPrior));
        legacyPrior.setEvidenceFingerprint("EV1");
        legacyPrior.setLastFullRunAt(null); // legacy row — never stamped
        assertFalse(svc.classify(freshLike(legacyPrior, "EV1"),
                        svc.indexPriorByIdentity(List.of(legacyPrior))).clean(),
                "valve (c): a legacy prior with no full-run timestamp is treated stale ⇒ DIRTY once");

        IdentifiedCondition recentPrior = cond("PTSD", "9411", "mental");
        recentPrior.setIdentityFingerprint(svc.computeIdentityFingerprint(recentPrior));
        recentPrior.setEvidenceFingerprint("EV1");
        recentPrior.setLastFullRunAt(java.time.Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS));
        assertTrue(svc.classify(freshLike(recentPrior, "EV1"),
                        svc.indexPriorByIdentity(List.of(recentPrior))).clean(),
                "a recently fully-run, fingerprint-clean condition stays CLEAN");
    }

    @Test
    void classify_flagOff_thirtyDayValveInert_todaysBehaviorByteForByte() {
        // Flag OFF (default construction): a stale lastFullRunAt must NOT force
        // dirty — classification is exactly the legacy fingerprint comparison.
        IdentifiedCondition prior = cond("PTSD", "9411", "mental");
        prior.setIdentityFingerprint(svc.computeIdentityFingerprint(prior));
        prior.setEvidenceFingerprint("EV1");
        prior.setLastFullRunAt(java.time.Instant.now().minus(400, java.time.temporal.ChronoUnit.DAYS));

        var decision = svc.classify(freshLike(prior, "EV1"), svc.indexPriorByIdentity(List.of(prior)));
        assertTrue(decision.clean(),
                "flag OFF ⇒ the 30-day valve is inert; identity+evidence match stays CLEAN");
    }

    @Test
    void carryForward_copiesLastFullRunAt() {
        IdentifiedCondition prior = cond("PTSD", "9411", "mental");
        java.time.Instant t = java.time.Instant.now().minus(3, java.time.temporal.ChronoUnit.DAYS);
        prior.setLastFullRunAt(t);
        IdentifiedCondition fresh = cond("PTSD", "9411", "mental");
        svc.carryForward(fresh, prior);
        assertEquals(t, fresh.getLastFullRunAt(),
                "carried outputs are as old as the prior's last full pass — the timestamp travels with them");
    }

    private IdentifiedCondition freshLike(IdentifiedCondition prior, String evidenceFp) {
        IdentifiedCondition fresh = cond(prior.getName(), prior.getVasrdCode(), prior.getBodySystem());
        fresh.setIdentityFingerprint(svc.computeIdentityFingerprint(fresh));
        fresh.setEvidenceFingerprint(evidenceFp);
        return fresh;
    }
}
