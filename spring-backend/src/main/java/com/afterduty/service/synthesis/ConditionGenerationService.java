package com.afterduty.service.synthesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.Atom;
import com.afterduty.model.Claim;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.model.Notification;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.IdentifiedConditionRepository;
import com.afterduty.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Mission 5b — condition generations: stable identity, atomic supersede, and
 * dirty-scope carry-forward.
 *
 * <p>Each synthesis run writes a fresh generation of {@link IdentifiedCondition}
 * rows. This service:
 * <ol>
 *   <li>computes a <b>stable identity fingerprint</b> per condition so the same
 *       real-world condition keeps a consistent identity across re-analysis
 *       (used to link old→new and to carry user-facing references forward);</li>
 *   <li>computes an <b>evidence fingerprint</b> over the live atom corpus that
 *       feeds the rating prompt, plus prompt/model versions;</li>
 *   <li>classifies each new-generation condition as CLEAN (identity AND evidence
 *       match a prior completed active condition ⇒ carry its rating / verification
 *       / gap outputs forward, submit no LLM jobs) or DIRTY (fan out);</li>
 *   <li>atomically supersedes the prior generation when the new one activates.</li>
 * </ol>
 *
 * <p>The identity fingerprint deliberately distinguishes laterality — a
 * bilateral, left-only, right-only, or dual-site condition sharing the same
 * diagnostic code must NOT collapse into one identity (the judges flagged this
 * exact mis-merge risk). Laterality is parsed from the condition name.
 *
 * <p>Everything here is a no-op unless the caller is running with the incremental
 * flag ON; with the flag OFF the synthesis machine never calls the
 * carry-forward / supersede paths, so behavior is the legacy append.
 */
@Service
public class ConditionGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ConditionGenerationService.class);

    /**
     * Versions baked into the evidence fingerprint. Bumping either invalidates
     * every carry-forward (forces a full re-rate next run) — the intended lever
     * when the rating prompt or routed model changes meaning. Kept here next to
     * the hash so the coupling is obvious.
     *
     * <p>Exposed via {@link com.afterduty.config.PromptVersionRegistry} (Increment
     * 8 eval harness) — bumping it fails the offline snapshot gate.
     */
    public static final String RATING_PROMPT_VERSION = "v1";

    /**
     * Phase B item B2 — PER-CONDITION evidence fingerprints (the rollback lever).
     * OFF (default): every condition's evidence fingerprint is the corpus-wide
     * hash — today's behavior byte-for-byte, one new fact dirties every condition.
     * ON: each condition is fingerprinted over ITS OWN attributed atoms only
     * ({@code supporting_atom_ids} from identify), so a new fact dirties only the
     * conditions that rest on it. Four safety valves: (a) unattributed conditions
     * fall back to the corpus-wide hash (strictly more conservative than a
     * body-system hash — atoms carry no body-system tag, and the corpus is a
     * superset of any body system); (b) new conditions are always dirty (no prior
     * identity match); (c) the 30-day full-refresh escape
     * ({@link #fullRefreshDays}); (d) this flag itself.
     *
     * <p>Field initializer keeps plain {@code new ConditionGenerationService(repo)}
     * unit construction on the OFF path.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${va-claim.synthesis.per-condition-fingerprint:false}")
    private boolean perConditionFingerprint = false;

    /**
     * Safety valve (c): a carried-forward-eligible condition whose
     * {@code last_full_run_at} is older than this many days (or null) goes DIRTY
     * on the next run, so no condition's rating/gaps can drift more than this far
     * from a full LLM pass under per-condition scoping. Only consulted when
     * {@link #perConditionFingerprint} is ON.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${va-claim.synthesis.full-refresh-days:30}")
    private int fullRefreshDays = 30;

    private final IdentifiedConditionRepository conditionRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Phase B item B1 (report §5 synthesis item 1, P1-8) — the "what changed"
     * signal. Optional (setter-injected, same pattern as
     * {@code SynthesisStateMachine.routingProperties}) so the existing pure unit
     * tests that construct this service with a lone repository keep working and
     * test slices that don't scan JPA repositories still wire. Absent ⇒ the flip
     * writes no notifications, everything else is unchanged.
     */
    @Nullable
    private NotificationRepository notificationRepository;

    @Nullable
    private ClaimRepository claimRepository;

    public ConditionGenerationService(IdentifiedConditionRepository conditionRepository) {
        this.conditionRepository = conditionRepository;
    }

    @Autowired(required = false)
    public void setNotificationRepository(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Autowired(required = false)
    public void setClaimRepository(ClaimRepository claimRepository) {
        this.claimRepository = claimRepository;
    }

    // -------------------------------------------------------------------------
    // Identity fingerprint
    // -------------------------------------------------------------------------

    /**
     * Stable identity = SHA-256 of normalized
     * {@code vasrd | body-system | connection-theory | laterality}. Two
     * conditions with the same fingerprint are "the same claimable condition"
     * across generations. Laterality and connection theory are derived from the
     * name so two same-DC bilateral/left/right rows stay distinct.
     */
    public String computeIdentityFingerprint(IdentifiedCondition c) {
        String vasrd = norm(c.getVasrdCode());
        if (vasrd.isEmpty()) vasrd = "novc";
        String system = norm(c.getBodySystem());
        String name = c.getName() == null ? "" : c.getName();
        String laterality = parseLaterality(name);
        String theory = parseConnectionTheory(name);

        // When there is no diagnostic code to anchor identity, fall back to the
        // normalized primary name (DC-less conditions must still get a stable,
        // distinguishing identity — otherwise every code-less row would share
        // "novc|...|..." and wrongly carry-forward into each other).
        String anchor = vasrd.equals("novc") ? "name:" + normName(name) : "vc:" + vasrd;

        String material = String.join("",
                anchor,
                "sys:" + system,
                "theory:" + theory,
                "lat:" + laterality);
        return sha256Hex(material);
    }

    /**
     * Parse laterality from a condition name. Bilateral, left, right, and a
     * dual-site pairing each map to a DISTINCT token so the identity fingerprint
     * separates them even under one diagnostic code. "Bilateral" wins over a lone
     * side word; an explicit "left ... right ..." (or "/") pairing is treated as a
     * dual-site condition.
     */
    String parseLaterality(String rawName) {
        String n = " " + (rawName == null ? "" : rawName.toLowerCase(Locale.ROOT)) + " ";
        boolean bilateral = n.contains("bilateral") || n.contains("both ")
                || n.contains(" b/l ") || n.contains("bilat");
        if (bilateral) return "bilateral";

        boolean left = n.contains(" left ") || n.contains(" lt ") || n.contains("(left")
                || n.contains(" l " ) || n.contains("left-") || n.contains("-left");
        boolean right = n.contains(" right ") || n.contains(" rt ") || n.contains("(right")
                || n.contains(" r ") || n.contains("right-") || n.contains("-right");

        if (left && right) return "dual:left+right";
        if (left) return "left";
        if (right) return "right";
        return "none";
    }

    /**
     * Connection theory derived from the name. A "(secondary to X)" qualifier is a
     * distinct theory keyed by the normalized parent so a secondary condition and
     * a same-DC direct one don't share identity. Default is direct service
     * connection.
     */
    String parseConnectionTheory(String rawName) {
        if (rawName == null) return "direct";
        String n = rawName.toLowerCase(Locale.ROOT);
        int idx = n.indexOf("secondary to");
        if (idx >= 0) {
            String parent = n.substring(idx + "secondary to".length());
            parent = parent.replaceAll("[)\\]].*$", "").trim();
            return "secondary:" + normName(parent);
        }
        if (n.contains("presumptive")) return "presumptive";
        if (n.contains("aggravat")) return "aggravation";
        return "direct";
    }

    // -------------------------------------------------------------------------
    // Evidence fingerprint
    // -------------------------------------------------------------------------

    /**
     * Evidence fingerprint over the live atom corpus that feeds the rating prompt
     * (the rating prompt sees the whole live atom set, so this is shared by every
     * condition in a run) plus the prompt/model versions. Atoms are sorted by a
     * deterministic natural key so insertion order never changes the hash. A new
     * run whose live atoms are byte-identical to the last completed run's (same
     * type/value/source AND same %.2f-rendered confidence — i.e. every field the
     * rating prompt renders, e.g. a duplicate-content upload that produced no new
     * live atoms) yields the SAME evidence fingerprint — that, combined with an
     * identity match, makes a condition CLEAN. Conversely, a re-extraction that
     * shifts an atom's confidence (which the rating prompt renders) changes the
     * hash, so the run is correctly DIRTY rather than a stale carry-forward.
     */
    public String computeEvidenceFingerprint(List<Atom> liveAtoms, String routedModelId) {
        List<String> keys = new ArrayList<>(liveAtoms.size());
        for (Atom a : liveAtoms) {
            // Natural key = (evidence_id, type, value, source, confidence). Value/
            // source are the actual rated content; evidence_id+type disambiguate
            // identical values from different docs. NOT the DB id (a re-extraction
            // mints new ids for byte-identical facts — using ids would make a no-op
            // upload look dirty).
            //
            // Confidence is in the key because the rating prompt RENDERS it:
            // RatingAgent.formatAtoms emits "... (source: %s, confidence: %.2f)".
            // Model-assigned per atom during extraction, it is non-deterministic
            // across re-extractions, so a re-extract that yields byte-identical
            // type/value/source but a DIFFERENT confidence genuinely changes the
            // text the rating model sees. If confidence were omitted, that change
            // would leave the evidence fingerprint unchanged — wrongly making the
            // run look like "no new facts" (false-positive short-circuit) and the
            // condition CLEAN (stale rating carried forward), so the changed
            // confidence would never reach the rating model. The fingerprint must
            // cover EVERY atom field the rating prompt renders. Rounded to the same
            // %.2f precision the prompt uses so sub-1% jitter that the model can't
            // even see doesn't needlessly invalidate carry-forward.
            keys.add(String.join("",
                    "ev:" + (a.getEvidenceId() == null ? "" : a.getEvidenceId()),
                    "ty:" + nz(a.getType()),
                    "va:" + nz(a.getValue()),
                    "so:" + nz(a.getSource()),
                    "cf:" + renderedConfidence(a.getConfidence())));
        }
        keys.sort(Comparator.naturalOrder());

        StringBuilder material = new StringBuilder();
        material.append("prompt:").append(RATING_PROMPT_VERSION).append('\n');
        material.append("model:").append(routedModelId == null ? "" : routedModelId).append('\n');
        material.append("n:").append(keys.size()).append('\n');
        for (String k : keys) material.append(k).append('\n');
        return sha256Hex(material.toString());
    }

    // -------------------------------------------------------------------------
    // Attribution at identify (Phase B item B2, report §5 items 2–4)
    // -------------------------------------------------------------------------

    /**
     * Build the attribution index for a run from the RAW identify output (the
     * pre-merge condition maps): identity fingerprint → union of the
     * {@code supporting_atom_ids} of every identify condition sharing that
     * identity. Duplicates the merger later absorbs share an identity fingerprint
     * (identity is anchored on vasrd|system|theory|laterality, not the free-text
     * name), so a merged condition inherits the UNION of its duplicates' atoms —
     * the merger's own output would only carry the kept row's list.
     */
    public Map<String, Set<Long>> buildAttributionIndex(List<Map<String, Object>> identifyConditionMaps) {
        Map<String, Set<Long>> byIdentity = new LinkedHashMap<>();
        if (identifyConditionMaps == null) return byIdentity;
        for (Map<String, Object> m : identifyConditionMaps) {
            if (m == null) continue;
            List<Long> ids = parseSupportingAtomIds(m);
            if (ids.isEmpty()) continue;
            byIdentity.computeIfAbsent(identityFingerprintOf(m), k -> new LinkedHashSet<>())
                    .addAll(ids);
        }
        return byIdentity;
    }

    /** Identity fingerprint of a raw identify/merge condition map (same recipe as the entity's). */
    private String identityFingerprintOf(Map<String, Object> condMap) {
        IdentifiedCondition probe = new IdentifiedCondition();
        probe.setName(condMap.get("name") instanceof String s ? s : null);
        probe.setVasrdCode(condMap.get("vasrd_code") instanceof String s ? s : null);
        probe.setBodySystem(condMap.get("body_system") instanceof String s ? s : null);
        return computeIdentityFingerprint(probe);
    }

    /**
     * Parse a condition map's {@code supporting_atom_ids} into atom ids. Tolerant
     * of the shapes a model actually emits: integers, numeric strings, and
     * "atom 12"/"A12"-style labels (digits are extracted). Unknown/absent ⇒ empty.
     */
    static List<Long> parseSupportingAtomIds(Map<String, Object> condMap) {
        Object raw = condMap == null ? null : condMap.get("supporting_atom_ids");
        if (!(raw instanceof List<?> list)) return List.of();
        List<Long> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Number n) {
                out.add(n.longValue());
            } else if (o instanceof String s) {
                String digits = s.replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) {
                    try {
                        out.add(Long.parseLong(digits));
                    } catch (NumberFormatException ignored) {
                        // absurdly long digit run — not an atom id; skip
                    }
                }
            }
        }
        return out;
    }

    /**
     * Persist the identify attribution onto a freshly mapped condition (item B2
     * step 1 — parse + persist, flag-independent: this is pure data). The
     * condition's own {@code supporting_atom_ids} (which survive the merger for
     * kept/standalone rows) are unioned with the attribution index entry for its
     * identity fingerprint (which recovers absorbed duplicates' atoms), then
     * filtered to ids that actually exist in the live corpus — a hallucinated id
     * must never anchor a fingerprint. Nothing usable ⇒ null (the
     * attribution-missing marker safety valve (a) keys off).
     */
    public void applyAttribution(IdentifiedCondition cond,
                                 Map<String, Object> condMap,
                                 Map<String, Set<Long>> attributionByIdentity,
                                 List<Atom> liveAtoms) {
        Set<Long> ids = new LinkedHashSet<>(parseSupportingAtomIds(condMap));
        if (attributionByIdentity != null) {
            Set<Long> fromIdentify = attributionByIdentity.get(computeIdentityFingerprint(cond));
            if (fromIdentify != null) ids.addAll(fromIdentify);
        }
        if (!ids.isEmpty() && liveAtoms != null) {
            Set<Long> liveIds = new LinkedHashSet<>();
            for (Atom a : liveAtoms) {
                if (a.getId() != null) liveIds.add(a.getId());
            }
            ids.retainAll(liveIds);
        }
        cond.setSupportingAtomIds(ids.isEmpty() ? null : new ArrayList<>(ids));
    }

    /**
     * Stamp the run's evidence fingerprints onto a fresh condition (item B2
     * step 2 — scoped fingerprints):
     * <ul>
     *   <li>{@code corpus_fingerprint} always gets the corpus-wide hash — the
     *       scheduler's no-new-facts short-circuit compares this column, so it
     *       keeps working whichever way the flag points;</li>
     *   <li>{@code evidence_fingerprint} gets the corpus-wide hash when the flag
     *       is OFF (today's behavior byte-for-byte) or when the condition is
     *       unattributed (safety valve (a): with no attribution the condition
     *       must dirty whenever ANY atom changes — the corpus hash is a strict
     *       superset of any body-system hash, so it is the conservative
     *       implementable form; atoms carry no body-system tag);</li>
     *   <li>with the flag ON and attribution present, {@code evidence_fingerprint}
     *       is the same hash recipe over ONLY the condition's attributed atoms —
     *       so an unrelated new fact no longer dirties it. The per-atom key is
     *       the natural key (evidence/type/value/source/rendered-confidence), so
     *       a re-extraction that mints new ids for byte-identical facts leaves
     *       the scoped fingerprint unchanged.</li>
     * </ul>
     * Call {@link #applyAttribution} first — this reads the persisted
     * {@code supportingAtomIds}.
     */
    public void stampEvidenceFingerprints(IdentifiedCondition cond,
                                          List<Atom> liveAtoms,
                                          String routedModelId,
                                          String corpusFingerprint) {
        cond.setCorpusFingerprint(corpusFingerprint);
        if (!perConditionFingerprint) {
            cond.setEvidenceFingerprint(corpusFingerprint);
            return;
        }
        List<Long> ids = cond.getSupportingAtomIds();
        if (ids == null || ids.isEmpty()) {
            // Safety valve (a) — attribution missing.
            cond.setEvidenceFingerprint(corpusFingerprint);
            return;
        }
        Set<Long> idSet = new LinkedHashSet<>(ids);
        List<Atom> scoped = new ArrayList<>();
        for (Atom a : liveAtoms) {
            if (a.getId() != null && idSet.contains(a.getId())) scoped.add(a);
        }
        if (scoped.isEmpty()) {
            // applyAttribution already filters to live ids, so this is belt-and-
            // braces for callers that stamped ids some other way.
            cond.setEvidenceFingerprint(corpusFingerprint);
            return;
        }
        cond.setEvidenceFingerprint(computeEvidenceFingerprint(scoped, routedModelId));
    }

    // -------------------------------------------------------------------------
    // Carry-forward classification
    // -------------------------------------------------------------------------

    /** Outcome of comparing one new-generation condition against the prior generation. */
    public record CarryDecision(boolean clean, IdentifiedCondition priorMatch) {
        static CarryDecision dirty() { return new CarryDecision(false, null); }
        static CarryDecision clean(IdentifiedCondition prior) { return new CarryDecision(true, prior); }
    }

    /**
     * Build an index of the prior generation's active conditions by identity
     * fingerprint. If two prior rows somehow share an identity (legacy data), the
     * higher-rated one wins — carry-forward should prefer the row a veteran is
     * most likely already referencing.
     */
    public Map<String, IdentifiedCondition> indexPriorByIdentity(List<IdentifiedCondition> prior) {
        Map<String, IdentifiedCondition> byId = new LinkedHashMap<>();
        for (IdentifiedCondition p : prior) {
            String fp = p.getIdentityFingerprint();
            if (fp == null) continue; // legacy rows can't match — forces a re-rate once
            IdentifiedCondition existing = byId.get(fp);
            if (existing == null || rating(p) > rating(existing)) {
                byId.put(fp, p);
            }
        }
        return byId;
    }

    /**
     * Decide CLEAN vs DIRTY for a freshly persisted new-generation condition whose
     * identity + evidence fingerprints are already set. CLEAN iff a prior active
     * condition shares the identity fingerprint AND its evidence fingerprint
     * equals this run's evidence fingerprint AND the prior actually completed
     * rating (has a non-null evidence fingerprint of its own). On CLEAN, the
     * caller copies the prior outputs and submits no rate/gap jobs.
     *
     * <p>Item B2 additions (behavior identical with the scoping flag OFF):
     * <ul>
     *   <li>safety valve (b) is structural — a NEW condition has no prior identity
     *       match and is always dirty;</li>
     *   <li>safety valve (c) — with {@code per-condition-fingerprint} ON, a
     *       fingerprint-clean condition whose prior {@code last_full_run_at} is
     *       older than {@link #fullRefreshDays} (or null/legacy) is forced DIRTY,
     *       so carried-forward outputs can never drift more than the window from
     *       a full LLM pass;</li>
     *   <li>{@code last_full_run_at} is stamped on the fresh row whenever the
     *       decision is DIRTY (the run is about to recompute its outputs) — a pure
     *       new-column write, flag-independent, priming the valve's data before
     *       the flag ever turns on. Clean rows get it copied in
     *       {@link #carryForward}.</li>
     * </ul>
     */
    public CarryDecision classify(IdentifiedCondition fresh,
                                  Map<String, IdentifiedCondition> priorByIdentity) {
        CarryDecision decision = classifyByFingerprints(fresh, priorByIdentity);
        if (decision.clean() && perConditionFingerprint && isStale(decision.priorMatch())) {
            // Safety valve (c) — 30-day full-refresh escape.
            log.info("[generation] condition '{}' fingerprint-clean but last full run is stale — "
                    + "forcing DIRTY (full-refresh valve)", fresh.getName());
            decision = CarryDecision.dirty();
        }
        if (!decision.clean()) {
            fresh.setLastFullRunAt(java.time.Instant.now());
        }
        return decision;
    }

    private CarryDecision classifyByFingerprints(IdentifiedCondition fresh,
                                                 Map<String, IdentifiedCondition> priorByIdentity) {
        String id = fresh.getIdentityFingerprint();
        if (id == null) return CarryDecision.dirty();
        IdentifiedCondition prior = priorByIdentity.get(id);
        if (prior == null) return CarryDecision.dirty();
        // The prior must have a completed evidence fingerprint to carry forward,
        // and it must equal this run's — otherwise the evidence that feeds the
        // rating changed and the condition is dirty.
        String priorEv = prior.getEvidenceFingerprint();
        if (priorEv == null) return CarryDecision.dirty();
        if (!priorEv.equals(fresh.getEvidenceFingerprint())) return CarryDecision.dirty();
        return CarryDecision.clean(prior);
    }

    /** Valve (c) staleness: null (legacy/never stamped) or older than the window. */
    private boolean isStale(IdentifiedCondition prior) {
        java.time.Instant last = prior == null ? null : prior.getLastFullRunAt();
        if (last == null) return true;
        return last.isBefore(java.time.Instant.now()
                .minus(fullRefreshDays, java.time.temporal.ChronoUnit.DAYS));
    }

    /** Test seam for the rollback-lever flag (Spring injects via @Value in production). */
    void setPerConditionFingerprint(boolean enabled) {
        this.perConditionFingerprint = enabled;
    }

    /** Test seam for the full-refresh window (valve (c)). */
    void setFullRefreshDays(int days) {
        this.fullRefreshDays = days;
    }

    /**
     * Copy the prior generation's completed outputs onto the fresh CLEAN row:
     * rating, rationale, confidence, gaps, what-if scenarios, and pyramiding
     * annotations. The triad/identity already came from this run's identify+merge
     * (which is cheap and always runs); only the expensive rate/gap LLM outputs
     * are carried. The caller persists {@code fresh}.
     *
     * <p>Gaps and what-if scenarios are normalized to a NON-null list (empty if
     * the prior had none). This is the durable dirty/clean signal the gap stage
     * keys off: a carried-forward clean condition always has non-null gaps (so the
     * gap fan-out skips it), while a freshly re-rated DIRTY condition has null gaps
     * (so the gap fan-out analyzes it). Without this normalization a clean
     * condition whose prior genuinely had zero gaps would be indistinguishable
     * from a dirty one and get needlessly re-analyzed.
     */
    public void carryForward(IdentifiedCondition fresh, IdentifiedCondition prior) {
        fresh.setEstimatedRating(prior.getEstimatedRating());
        fresh.setRatingRationale(prior.getRatingRationale());
        fresh.setConfidence(prior.getConfidence());
        fresh.setGaps(prior.getGaps() != null ? prior.getGaps() : new ArrayList<>());
        fresh.setWhatIfScenarios(reanchorWhatIfScenarios(prior.getWhatIfScenarios(), fresh.getId()));
        fresh.setPyramidGroup(prior.getPyramidGroup());
        fresh.setPyramidReason(prior.getPyramidReason());
        // Item B2 valve (c) bookkeeping: the carried outputs are as old as the
        // prior's last full pass, so the timestamp travels with them.
        fresh.setLastFullRunAt(prior.getLastFullRunAt());
    }

    /**
     * Copy the prior generation's what-if scenarios onto the carried-forward CLEAN
     * row, RE-ANCHORING any embedded condition-id reference to the fresh (active)
     * condition's id. A what-if scenario can carry a nested {@code conditionId} /
     * {@code condition_id} pointing at the condition it was generated for; if copied
     * verbatim it would still reference the PRIOR generation's (now-superseded) row
     * while the enclosing condition is the new active one — a latent inconsistency
     * surfaced raw to clients (ConditionResponse.whatIfScenarios). The design
     * promised these references survive re-analysis, so each carried scenario's
     * nested condition id is rewritten to {@code freshId}.
     *
     * <p>Each scenario map is copied (the carried list and its maps are fresh
     * instances) so mutating the rewrite never aliases back into the prior row's
     * persisted JSON. Returns a NON-null list (empty if the prior had none) — the
     * durable clean/dirty signal {@link #needsGapAnalysis} keys off. A no-op when no
     * scenario carries a condition-id key.
     */
    static List<Map<String, Object>> reanchorWhatIfScenarios(List<Map<String, Object>> prior, Long freshId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (prior == null) return out;
        for (Map<String, Object> scenario : prior) {
            if (scenario == null) {
                out.add(null);
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(scenario);
            // Rewrite whichever nested key the payload used; only touch keys that are
            // actually present so we never inject a spurious field.
            if (freshId != null) {
                if (copy.containsKey("conditionId")) copy.put("conditionId", freshId);
                if (copy.containsKey("condition_id")) copy.put("condition_id", freshId);
            }
            out.add(copy);
        }
        return out;
    }

    /**
     * Whether a condition still needs gap analysis (is DIRTY for the gap stage).
     * A condition that was carried forward clean has non-null gaps (see
     * {@link #carryForward}); a freshly re-rated dirty condition has null gaps.
     * With the incremental flag OFF every condition has null gaps after rating, so
     * this returns true for all of them — the full gap fan-out, unchanged.
     */
    public boolean needsGapAnalysis(IdentifiedCondition c) {
        return c.getGaps() == null;
    }

    // -------------------------------------------------------------------------
    // Atomic supersede
    // -------------------------------------------------------------------------

    /**
     * Mark every condition in {@code prior} (the previously-active generation)
     * superseded, pointing each old row at its replacement in {@code current}
     * (the just-activated generation) where an identity match exists, else at a
     * tombstone marker so the row is still excluded by {@code superseded_by IS
     * NULL} but is distinguishable from a real replacement pointer.
     *
     * <p>Called inside the SAME transaction that activates the new generation, so
     * a reader either sees the whole old generation (before commit) or the whole
     * new generation (after commit) — never zero rows and never both.
     *
     * <p>Tombstone convention: {@link #TOMBSTONE_MARKER} (a sentinel that is not a
     * valid condition id) means "retired with no surviving replacement" (the
     * condition dropped out of the new generation entirely). A positive value is
     * the id of the replacing condition, so chat citations / scenario references
     * to the old row can be redirected to the live one.
     */
    public void supersedePriorGeneration(List<IdentifiedCondition> prior,
                                         List<IdentifiedCondition> current) {
        // Phase B item B1 — deterministic diff-at-flip. This method is the flip:
        // it runs inside the same @Transactional advance() that just activated the
        // new generation, with both generations in hand (prior still readable,
        // current already carrying its final rating/triad/gaps for this run). The
        // diff + Notification writes commit atomically with the flip, and a
        // failure inside the writer can never block or roll it back (try/catch
        // inside writeFlipNotifications). Runs BEFORE the pointer mutation purely
        // for clarity — the diff never reads superseded_by.
        writeFlipNotifications(prior, current);

        Map<String, IdentifiedCondition> currentByIdentity = new LinkedHashMap<>();
        for (IdentifiedCondition c : current) {
            String fp = c.getIdentityFingerprint();
            if (fp != null) currentByIdentity.putIfAbsent(fp, c);
        }

        int replaced = 0;
        int tombstoned = 0;
        for (IdentifiedCondition old : prior) {
            if (old.getSupersededBy() != null) continue; // already retired (e.g. same-run dedup)
            IdentifiedCondition replacement = old.getIdentityFingerprint() == null
                    ? null
                    : currentByIdentity.get(old.getIdentityFingerprint());
            if (replacement != null && replacement.getId() != null) {
                old.setSupersededBy(replacement.getId());
                replaced++;
            } else {
                old.setSupersededBy(TOMBSTONE_MARKER);
                tombstoned++;
            }
            conditionRepository.save(old);
        }
        if (replaced + tombstoned > 0) {
            log.info("[generation] superseded prior generation: {} replaced, {} tombstoned",
                    replaced, tombstoned);
        }
    }

    /**
     * Tombstone marker for {@code superseded_by}: a retired condition with no
     * surviving replacement. Negative so it can never collide with a real
     * (positive, auto-increment) condition id. Any non-null value excludes the row
     * from the active-generation readers.
     */
    public static final long TOMBSTONE_MARKER = -1L;

    /**
     * In-run pending marker for {@code superseded_by}: a new-generation condition
     * that has been persisted but NOT yet activated. While a run is mid-flight the
     * new rows carry this marker so external readers (filtering {@code
     * superseded_by IS NULL}) keep seeing the PRIOR generation; at COMPLETE the
     * pending rows are flipped to null (activated) in the same transaction that
     * retires the prior generation, so a reader sees exactly one full generation
     * at every instant. Negative and distinct from the tombstone so the two never
     * collide with each other or with a real condition id.
     */
    public static final long PENDING_MARKER = -2L;

    /**
     * Activate the pending new generation: clear the PENDING marker so the rows
     * become visible to the active-generation readers. Called at COMPLETE, in the
     * SAME transaction as {@link #supersedePriorGeneration}, so the flip from old
     * generation to new is atomic.
     */
    public void activatePendingGeneration(List<IdentifiedCondition> pending) {
        for (IdentifiedCondition c : pending) {
            if (c.getSupersededBy() != null && c.getSupersededBy() == PENDING_MARKER) {
                c.setSupersededBy(null);
                conditionRepository.save(c);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Diff-at-flip → Notification rows (Phase B item B1, report §5 item 1 / P1-8)
    // -------------------------------------------------------------------------

    /**
     * Cap on granular rows per flip (the digest row is always written on top of
     * these; its metadata carries the FULL diff, so nothing is lost when granular
     * rows are skipped past the cap).
     */
    static final int MAX_GRANULAR_ROWS_PER_FLIP = 8;

    /** Notification event types written at the generation flip. */
    static final String EVENT_ANALYSIS_UPDATED = "analysis_updated";
    static final String EVENT_ANALYSIS_COMPLETE = "analysis_complete";
    static final String EVENT_CONDITION_ADDED = "condition_added";
    static final String EVENT_RATING_CHANGED = "rating_changed";

    private record RatingChange(Long conditionId, String name, Integer from, Integer to) {}

    private record LegChange(Long conditionId, String name, String leg, String from, String to) {}

    /** Deterministic per-flip diff between the outgoing and incoming generations. */
    private record GenerationDiff(List<RatingChange> ratingChanges,
                                  List<LegChange> legChanges,
                                  int gapsClosed,
                                  int gapsOpened,
                                  List<IdentifiedCondition> added,
                                  List<IdentifiedCondition> retired,
                                  Set<Long> changedConditionIds) {
        boolean material() {
            return !ratingChanges.isEmpty() || !legChanges.isEmpty()
                    || gapsClosed > 0 || gapsOpened > 0
                    || !added.isEmpty() || !retired.isEmpty();
        }
    }

    /**
     * Compute the per-condition diff between the outgoing generation and the one
     * that just activated, and persist it as {@link Notification} rows — the
     * "your nexus letter worked" signal (P1-8). Everything the veteran reads here
     * is a string template over the deterministic diff; no LLM writes any number
     * or diff text (the report's kill-list rule).
     *
     * <p>Vocabulary (pinned contract):
     * <ul>
     *   <li>{@code analysis_updated} — ONE digest row per flip that changed
     *       anything; {@code metadataJson} carries the full machine-readable diff.</li>
     *   <li>{@code rating_changed} / {@code condition_added} — granular rows,
     *       capped at {@link #MAX_GRANULAR_ROWS_PER_FLIP} per flip.</li>
     *   <li>{@code analysis_complete} — first-ever analysis (no prior generation)
     *       writes this single row INSTEAD of a diff.</li>
     * </ul>
     *
     * <p>Identical generations write nothing. Gap deltas are counted only for
     * identity-matched pairs where BOTH sides have a non-null gap list: at the
     * synthesis flip a DIRTY condition's gaps are still null (the gap fan-out runs
     * after), so treating null as "all closed" would fabricate a claim the run
     * never made — we deterministically undercount rather than ever overstate.
     *
     * <p>Failure isolation: the whole diff+write is wrapped in try/catch — a
     * notification bug must NEVER block or roll back the generation flip. Writes
     * happen row-by-row via {@code save()} (IDENTITY ids flush immediately), so a
     * failing insert surfaces here, inside the catch, not at commit.
     */
    void writeFlipNotifications(List<IdentifiedCondition> prior, List<IdentifiedCondition> current) {
        try {
            if (notificationRepository == null || claimRepository == null) return; // not wired (unit slices)

            Long claimId = firstClaimId(prior, current);
            if (claimId == null) return; // nothing in either generation
            Claim claim = claimRepository.findById(claimId).orElse(null);
            if (claim == null || claim.getUserId() == null) {
                log.warn("[generation] flip notifications skipped — claim {} not resolvable to a user", claimId);
                return;
            }
            Long userId = claim.getUserId();
            String runId = UUID.randomUUID().toString();

            if (prior.isEmpty()) {
                // First-ever analysis (no prior generation): a diff against nothing
                // is not an "update" — write the single analysis_complete row.
                if (current.isEmpty()) return;
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("runId", runId);
                meta.put("conditionCount", current.size());
                // Every first-run condition IS an addition — reuse the digest
                // schema's addedConditions key so the card/timeline render the
                // found list with the same code path as an update's diff.
                List<Map<String, Object>> found = new ArrayList<>();
                for (IdentifiedCondition c : current) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("conditionId", c.getId());
                    m.put("name", c.getName());
                    found.add(m);
                }
                meta.put("addedConditions", found);
                notificationRepository.save(Notification.builder()
                        .userId(userId).claimId(claimId)
                        .eventType(EVENT_ANALYSIS_COMPLETE)
                        .title("Analysis complete")
                        .body("We found " + current.size() + plural(current.size(), " condition", " conditions")
                                + " in your records.")
                        .severity("success")
                        .metadataJson(toJson(meta))
                        .build());
                return;
            }

            GenerationDiff diff = computeGenerationDiff(prior, current);
            if (!diff.material()) return; // identical generations — no rows

            // Granular rows first, digest LAST: reads order createdAt DESC, id DESC,
            // so the digest (highest id in the flip) surfaces on top.
            int granular = 0;
            for (RatingChange rc : diff.ratingChanges()) {
                if (granular >= MAX_GRANULAR_ROWS_PER_FLIP) break;
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("runId", runId);
                meta.put("from", rc.from());
                meta.put("to", rc.to());
                notificationRepository.save(Notification.builder()
                        .userId(userId).claimId(claimId)
                        .eventType(EVENT_RATING_CHANGED)
                        .title("Rating estimate updated")
                        .body(ratingBody(rc))
                        .severity(ratingWentUp(rc) ? "success" : "info")
                        .conditionId(rc.conditionId())
                        .metadataJson(toJson(meta))
                        .build());
                granular++;
            }
            for (IdentifiedCondition added : diff.added()) {
                if (granular >= MAX_GRANULAR_ROWS_PER_FLIP) break;
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("runId", runId);
                notificationRepository.save(Notification.builder()
                        .userId(userId).claimId(claimId)
                        .eventType(EVENT_CONDITION_ADDED)
                        .title("New condition identified")
                        .body(added.getName() + " was added to your claim analysis.")
                        .severity("success")
                        .conditionId(added.getId())
                        .metadataJson(toJson(meta))
                        .build());
                granular++;
            }

            notificationRepository.save(Notification.builder()
                    .userId(userId).claimId(claimId)
                    .eventType(EVENT_ANALYSIS_UPDATED)
                    .title("Your analysis was updated")
                    .body(digestBody(diff))
                    .severity("info")
                    .metadataJson(toJson(digestMetadata(runId, diff)))
                    .build());
        } catch (Exception e) {
            // A diff/notification failure must never block or roll back the flip.
            log.error("[generation] flip notification write failed — flip unaffected", e);
        }
    }

    /** Diff the two generations, matched by identity fingerprint. */
    private GenerationDiff computeGenerationDiff(List<IdentifiedCondition> prior,
                                                 List<IdentifiedCondition> current) {
        // Prior side deduped exactly like carry-forward (highest-rated row wins) so
        // the diff compares against the same row the veteran was referencing.
        Map<String, IdentifiedCondition> priorByIdentity = indexPriorByIdentity(prior);

        List<RatingChange> ratingChanges = new ArrayList<>();
        List<LegChange> legChanges = new ArrayList<>();
        List<IdentifiedCondition> added = new ArrayList<>();
        Set<Long> changed = new LinkedHashSet<>();
        int gapsClosed = 0;
        int gapsOpened = 0;

        Set<String> matchedFps = new LinkedHashSet<>();
        for (IdentifiedCondition cur : current) {
            String fp = cur.getIdentityFingerprint();
            // No fingerprint, no prior match, or a same-fp duplicate beyond the
            // first (supersede points prior rows at the FIRST current match) ⇒ new.
            IdentifiedCondition prev = fp == null ? null : priorByIdentity.get(fp);
            if (prev == null || !matchedFps.add(fp)) {
                added.add(cur);
                continue;
            }

            Integer from = prev.getEstimatedRating();
            Integer to = cur.getEstimatedRating();
            if (!Objects.equals(from, to)) {
                ratingChanges.add(new RatingChange(cur.getId(), cur.getName(), from, to));
                changed.add(cur.getId());
            }

            recordLegChange(legChanges, changed, cur, "dx", prev.getTriadDiagnosis(), cur.getTriadDiagnosis());
            recordLegChange(legChanges, changed, cur, "is", prev.getTriadInService(), cur.getTriadInService());
            recordLegChange(legChanges, changed, cur, "nx", prev.getTriadNexus(), cur.getTriadNexus());

            // Gap delta only when BOTH sides actually have a gap list (see javadoc).
            if (prev.getGaps() != null && cur.getGaps() != null) {
                Map<String, Integer> before = openGapCounts(prev.getGaps());
                Map<String, Integer> after = openGapCounts(cur.getGaps());
                Set<String> keys = new LinkedHashSet<>(before.keySet());
                keys.addAll(after.keySet());
                for (String key : keys) {
                    int b = before.getOrDefault(key, 0);
                    int a = after.getOrDefault(key, 0);
                    if (a < b) gapsClosed += b - a;
                    if (a > b) gapsOpened += a - b;
                    if (a != b) changed.add(cur.getId());
                }
            }
        }

        // Retired = prior rows the supersede below will tombstone: no fingerprint,
        // or no identity match in the new generation.
        Set<String> currentFps = new LinkedHashSet<>();
        for (IdentifiedCondition c : current) {
            if (c.getIdentityFingerprint() != null) currentFps.add(c.getIdentityFingerprint());
        }
        List<IdentifiedCondition> retired = new ArrayList<>();
        for (IdentifiedCondition old : prior) {
            String fp = old.getIdentityFingerprint();
            if (fp == null || !currentFps.contains(fp)) retired.add(old);
        }

        return new GenerationDiff(ratingChanges, legChanges, gapsClosed, gapsOpened, added, retired, changed);
    }

    private static void recordLegChange(List<LegChange> out, Set<Long> changed, IdentifiedCondition cur,
                                        String leg, Map<String, Object> before, Map<String, Object> after) {
        String from = legStatus(before);
        String to = legStatus(after);
        if (from.equals(to)) return;
        out.add(new LegChange(cur.getId(), cur.getName(), leg, from, to));
        changed.add(cur.getId());
    }

    /**
     * Digest body — string templates over the deterministic diff, e.g.
     * "Your new evidence updated 2 conditions: PTSD rating estimate 50% -> 70%;
     * Right knee nexus evidence strengthened. 1 evidence gap closed."
     */
    private static String digestBody(GenerationDiff diff) {
        List<String> clauses = new ArrayList<>();
        for (RatingChange rc : diff.ratingChanges()) clauses.add(ratingClause(rc));
        for (LegChange lc : diff.legChanges()) clauses.add(legClause(lc));

        Set<Long> clauseConditions = new LinkedHashSet<>();
        for (RatingChange rc : diff.ratingChanges()) clauseConditions.add(rc.conditionId());
        for (LegChange lc : diff.legChanges()) clauseConditions.add(lc.conditionId());

        StringBuilder body = new StringBuilder();
        if (!clauses.isEmpty()) {
            int n = clauseConditions.size();
            // Bounded: a big re-analysis (dozens of changes) must stay a readable
            // sentence — the card/timeline render the full detail from metadata.
            int shown = Math.min(clauses.size(), 3);
            body.append("Your new evidence updated ").append(n)
                    .append(plural(n, " condition: ", " conditions: "))
                    .append(String.join("; ", clauses.subList(0, shown)));
            if (clauses.size() > shown) {
                body.append("; and ").append(clauses.size() - shown).append(" more");
            }
            body.append('.');
        } else {
            body.append("Your analysis was updated.");
        }
        if (diff.gapsClosed() > 0) {
            body.append(' ').append(diff.gapsClosed())
                    .append(plural(diff.gapsClosed(), " evidence gap", " evidence gaps")).append(" closed.");
        }
        if (diff.gapsOpened() > 0) {
            body.append(' ').append(diff.gapsOpened())
                    .append(plural(diff.gapsOpened(), " new evidence gap", " new evidence gaps")).append(" identified.");
        }
        // Counts only — enumerating 12 condition names made the digest an
        // unreadable wall (2026-07-03 screenshot); names live in metadata.
        if (!diff.added().isEmpty()) {
            body.append(' ').append(diff.added().size())
                    .append(plural(diff.added().size(), " new condition", " new conditions"))
                    .append(" found.");
        }
        if (!diff.retired().isEmpty()) {
            body.append(' ').append(diff.retired().size())
                    .append(plural(diff.retired().size(), " condition", " conditions"))
                    .append(" no longer supported by the evidence.");
        }
        return body.toString();
    }

    /** Full machine-readable diff for the digest row (pinned metadata schema). */
    private static Map<String, Object> digestMetadata(String runId, GenerationDiff diff) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("runId", runId);
        List<Map<String, Object>> ratingChanges = new ArrayList<>();
        for (RatingChange rc : diff.ratingChanges()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("conditionId", rc.conditionId());
            m.put("name", rc.name());
            m.put("from", rc.from());
            m.put("to", rc.to());
            ratingChanges.add(m);
        }
        meta.put("ratingChanges", ratingChanges);
        List<Map<String, Object>> legChanges = new ArrayList<>();
        for (LegChange lc : diff.legChanges()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("conditionId", lc.conditionId());
            m.put("name", lc.name());
            m.put("leg", lc.leg());
            m.put("from", lc.from());
            m.put("to", lc.to());
            legChanges.add(m);
        }
        meta.put("legChanges", legChanges);
        meta.put("gapsClosed", diff.gapsClosed());
        meta.put("gapsOpened", diff.gapsOpened());
        List<Map<String, Object>> addedConditions = new ArrayList<>();
        for (IdentifiedCondition c : diff.added()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("conditionId", c.getId());
            m.put("name", c.getName());
            addedConditions.add(m);
        }
        meta.put("addedConditions", addedConditions);
        List<Map<String, Object>> retiredConditions = new ArrayList<>();
        for (IdentifiedCondition c : diff.retired()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.getName());
            retiredConditions.add(m);
        }
        meta.put("retiredConditions", retiredConditions);
        meta.put("changedConditionIds", new ArrayList<>(diff.changedConditionIds()));
        return meta;
    }

    private static String ratingClause(RatingChange rc) {
        if (rc.from() != null && rc.to() != null) {
            return rc.name() + " rating estimate " + rc.from() + "% -> " + rc.to() + "%";
        }
        if (rc.to() != null) return rc.name() + " rating estimate now " + rc.to() + "%";
        return rc.name() + " rating estimate no longer available";
    }

    /**
     * Granular rating body — states both numbers plainly, never spins a decrease
     * (pinned contract for {@code rating_changed}).
     */
    private static String ratingBody(RatingChange rc) {
        if (rc.from() != null && rc.to() != null) {
            return rc.name() + " rating estimate changed from " + rc.from() + "% to " + rc.to() + "%.";
        }
        if (rc.to() != null) return rc.name() + " rating estimate is now " + rc.to() + "%.";
        return rc.name() + " rating estimate is no longer available.";
    }

    private static boolean ratingWentUp(RatingChange rc) {
        if (rc.to() == null) return false;
        return rc.from() == null || rc.to() > rc.from();
    }

    private static String legClause(LegChange lc) {
        int fromRank = legRank(lc.from());
        int toRank = legRank(lc.to());
        String direction;
        if (fromRank >= 0 && toRank >= 0) {
            direction = toRank > fromRank ? "strengthened" : "weakened";
        } else {
            direction = "changed"; // an unrecognized status level — never guess a direction
        }
        return lc.name() + " " + legHuman(lc.leg()) + " evidence " + direction;
    }

    private static String legHuman(String leg) {
        return switch (leg) {
            case "dx" -> "diagnosis";
            case "is" -> "in-service";
            case "nx" -> "nexus";
            default -> leg;
        };
    }

    /** Triad leg status as identify writes it (STRONG|MODERATE|WEAK|MISSING); absent ⇒ MISSING. */
    private static String legStatus(Map<String, Object> leg) {
        Object status = leg == null ? null : leg.get("status");
        String s = status == null ? "" : status.toString().trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? "MISSING" : s;
    }

    private static int legRank(String status) {
        return switch (status) {
            case "MISSING" -> 0;
            case "WEAK" -> 1;
            case "MODERATE" -> 2;
            case "STRONG" -> 3;
            default -> -1;
        };
    }

    /**
     * Count OPEN gaps per (type, triad_leg) key — the same identity
     * {@code user_gap_state} keys on. Gaps a user already resolved/dismissed
     * (P1-6 status field) don't count as "closed again" when they disappear.
     */
    private static Map<String, Integer> openGapCounts(List<Map<String, Object>> gaps) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> g : gaps) {
            if (g == null) continue;
            Object status = g.get("status");
            String s = status == null ? "open" : status.toString().trim().toLowerCase(Locale.ROOT);
            if (!s.isEmpty() && !s.equals("open")) continue;
            String key = g.get("type") + "|" + g.get("triad_leg");
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    private static String names(List<IdentifiedCondition> conditions) {
        List<String> names = new ArrayList<>(conditions.size());
        for (IdentifiedCondition c : conditions) names.add(c.getName());
        return String.join(", ", names);
    }

    private static String plural(int n, String singular, String plural) {
        return n == 1 ? singular : plural;
    }

    @Nullable
    private static Long firstClaimId(List<IdentifiedCondition> prior, List<IdentifiedCondition> current) {
        for (IdentifiedCondition c : current) {
            if (c.getClaimId() != null) return c.getClaimId();
        }
        for (IdentifiedCondition c : prior) {
            if (c.getClaimId() != null) return c.getClaimId();
        }
        return null;
    }

    private String toJson(Map<String, Object> value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static int rating(IdentifiedCondition c) {
        return c.getEstimatedRating() != null ? c.getEstimatedRating() : 0;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /** Collapse a free-text name to a stable comparison token (alnum + single spaces). */
    private static String normName(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * Render an atom's confidence EXACTLY as the rating prompt does — {@code %.2f}
     * via {@link String#format} with a fixed locale — so the evidence fingerprint
     * changes iff the text the rating model sees changes. Null confidence (legacy /
     * never-set) maps to a stable sentinel rather than throwing, so a null→0.50
     * transition is correctly a fingerprint change. {@link Locale#ROOT} keeps the
     * decimal separator a '.' regardless of the host locale (the prompt uses the
     * default-locale {@code String.format}, but both run on the server JVM and we
     * only need self-consistency of the hash, which {@link Locale#ROOT} guarantees).
     */
    static String renderedConfidence(Double confidence) {
        if (confidence == null) return "null";
        return String.format(Locale.ROOT, "%.2f", confidence);
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // SHA-256 is always present on a JVM; an impossible failure must not be
            // swallowed into a wrong "match" — fail loud so a run errors rather
            // than silently carrying forward stale ratings.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
