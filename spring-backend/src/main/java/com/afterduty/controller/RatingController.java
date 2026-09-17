package com.afterduty.controller;

import com.afterduty.model.Claim;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.model.User;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.ConditionRepository;
import com.afterduty.service.AccessScope;
import com.afterduty.service.ClaimAccess;
import com.afterduty.service.ClaimAccessService;
import com.afterduty.service.VaMathService;
import com.afterduty.service.synthesis.PyramidingGroups;
import com.afterduty.service.synthesis.PyramidingRules;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.afterduty.controller.AuthController.getCurrentUser;

/**
 * C1 (report P1-1) — the ONE authoritative combined-rating endpoint.
 *
 * <p>{@code GET /api/claim/combined-rating?scope=all|ready} runs
 * {@link PyramidingRules#plan} over the scope's ACTIVE conditions and feeds the
 * plan's countable ratings + bilateral pairs through {@link VaMathService} —
 * the exact call pattern {@code ClaudeSynthesisService} already computes and
 * discards. Before this endpoint, every headline number the veteran saw was a
 * raw combination of all rating&gt;0 conditions: pyramid-absorbed rows inflated
 * it (PTSD 70 + absorbed anxiety 50 combined) and bilateral pairs understated
 * it (two knees never got the §4.26 +10% factor).
 *
 * <p>Kill-list rule: every number in this response (dollars, ratings, the
 * step-by-step walk) is produced deterministically by
 * {@link PyramidingRules} + {@link VaMathService}. No LLM output is consulted
 * at request time.
 *
 * <p>Response contract (web consumes; keep stable):
 * <pre>
 * {
 *   "scope": "all"|"ready",
 *   "combinedRating": int,          // VA-rounded, after pyramiding plan + bilateral
 *   "monthlyEstimate": number,      // dollars, veteran-alone rate
 *   "ratesYear": int,               // e.g. 2026 — VaMathService.RATES_YEAR
 *   "notes": [string],              // human-readable absorption / cap / bilateral / excluded / assumption notes
 *   "steps": [string],              // VaMathService's abstract step-by-step combination walk
 *   "combineSteps": [{              // OPTIONAL labeled sequential combine (one per contributor,
 *       "label", "rating"|null,     //   descending) + a final rounding step. Omitted on divergence
 *       "pointsAdded", "combinedAfter", "remainingAfter",
 *       "absorbedMembers": [string], "rounding": bool }],
 *   "inputs": [{ "conditionId", "name", "rating", "counted",
 *                "reason": null | "not-ready" | "pyramided-into:<label>",
 *                "group": null | "<humanized pyramid group>" }],
 *   "excludedCount": int            // ACTIVE rating&gt;0 rows the veteran left out ("Don't include in my claim")
 * }
 * </pre>
 *
 * <ul>
 *   <li>{@code scope=all} — every active condition with a rating &gt; 0.</li>
 *   <li>{@code scope=ready} — only conditions whose three evidence legs
 *       (diagnosis / in-service / nexus) are all STRONG, mirroring the web's
 *       readiness heuristic ({@code condition.ts}) and
 *       {@code ConditionGenerationService.legStatus}.</li>
 *   <li>No claim / empty claim → 200 with combinedRating 0 and empty inputs
 *       (never 404 — the web renders its estimate-unavailable state only on
 *       fetch failure).</li>
 * </ul>
 */
@RestController
public class RatingController {

    private static final Logger log = LoggerFactory.getLogger(RatingController.class);

    private final ClaimRepository claimRepository;
    private final ConditionRepository conditionRepository;
    private final ClaimAccessService claimAccessService;
    private final PyramidingRules pyramidingRules;
    private final PyramidingGroups pyramidingGroups;
    private final VaMathService vaMathService;

    public RatingController(ClaimRepository claimRepository,
                            ConditionRepository conditionRepository,
                            ClaimAccessService claimAccessService,
                            PyramidingRules pyramidingRules,
                            PyramidingGroups pyramidingGroups,
                            VaMathService vaMathService) {
        this.claimRepository = claimRepository;
        this.conditionRepository = conditionRepository;
        this.claimAccessService = claimAccessService;
        this.pyramidingRules = pyramidingRules;
        this.pyramidingGroups = pyramidingGroups;
        this.vaMathService = vaMathService;
    }

    /**
     * A condition that survived the pyramiding filter, with the effective rating
     * that actually enters the combination (tinnitus capped per §4.87 / 6260).
     */
    private record Survivor(IdentifiedCondition condition, int effective) {}

    @GetMapping("/api/claim/combined-rating")
    public Map<String, Object> combinedRating(
            @RequestParam(name = "scope", defaultValue = "all") String scope,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        if (!"all".equals(scope) && !"ready".equals(scope)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_scope");
        }
        boolean readyScope = "ready".equals(scope);

        Long claimId = resolveClaimId(user, request);
        if (claimId == null) {
            // No claim yet — contract says 200 zero-state, never 404.
            return zeroBody(scope);
        }

        // ACTIVE generation only (Mission 5b) — a re-analysis flip must never
        // combine retired rows. Unrated rows (null/0) can't enter §4.25 math and
        // are omitted from inputs too ("every active condition WITH a rating").
        // "Don't include in my claim" (owner-set, reversible): an excluded condition
        // is VALID but the veteran isn't filing for it, so it never enters the
        // pyramiding plan / VA math and never appears in `inputs` here. It stays in
        // the conditions LIST (IntakeController returns it, flagged) so the web can
        // show it in the "Not filing" section — only this combined-rating math drops
        // it. This filter shared by both scope=all and scope=ready (below).
        // Base query: ACTIVE (non-superseded), actually-rated (rating>0) rows.
        // Both the exclude filter AND the excludedCount below derive from this
        // exact set, so the count is precisely "the rows line 133 dropped".
        List<IdentifiedCondition> ratedBeforeExclude = conditionRepository
                .findByClaimIdAndSupersededByIsNull(claimId).stream()
                .filter(c -> c.getEstimatedRating() != null && c.getEstimatedRating() > 0)
                .toList();

        // "Don't include in my claim" — count the valid, rated conditions the
        // veteran chose to leave out (drives the "N excluded" line). Same predicate
        // as the drop filter, so it can never disagree with what actually fell out.
        long excludedCount = ratedBeforeExclude.stream()
                .filter(c -> Boolean.TRUE.equals(c.getExcludedFromClaim()))
                .count();

        List<IdentifiedCondition> rated = ratedBeforeExclude.stream()
                .filter(c -> !Boolean.TRUE.equals(c.getExcludedFromClaim()))
                .toList();

        List<IdentifiedCondition> inScope = readyScope
                ? rated.stream().filter(RatingController::allLegsStrong).toList()
                : rated;
        Set<Long> inScopeIds = inScope.stream()
                .map(IdentifiedCondition::getId)
                .collect(Collectors.toSet());

        // The authoritative math: PyramidingRules.plan → VaMathService (§4.25/§4.26),
        // mirroring ClaudeSynthesisService's (previously discarded) computation.
        PyramidingRules.Plan plan = pyramidingRules.plan(inScope);
        Map<String, Object> calc =
                vaMathService.calculateCombinedRating(plan.ratings(), plan.bilateralPairs());
        int combinedRating = (Integer) calc.get("combined_rating");

        // ---- Per-condition classification for inputs/notes. Mirrors the plan's
        // three rules exactly (absorbed: non-blank pyramidReason; tinnitus cap:
        // PyramidingRules.TINNITUS_CODE/MAX; bilateral: exactly two survivors
        // sharing a VASRD code). The NUMBERS above always come from the plan —
        // this mirror only labels rows; a divergence is logged, never rendered
        // as a different dollar figure.
        List<Survivor> survivors = new ArrayList<>();
        for (IdentifiedCondition c : inScope) {
            if (isAbsorbed(c)) continue;
            survivors.add(new Survivor(c, effectiveRating(c)));
        }

        Map<String, List<Survivor>> byCode = survivors.stream()
                .filter(s -> s.condition().getVasrdCode() != null
                        && !s.condition().getVasrdCode().isBlank())
                .collect(Collectors.groupingBy(s -> s.condition().getVasrdCode()));
        List<List<Survivor>> bilateralPairs = byCode.values().stream()
                .filter(g -> g.size() == 2)
                .toList();

        List<String> notes = new ArrayList<>();
        List<Map<String, Object>> inputs = new ArrayList<>();
        int notReadyCount = 0;
        int countedCount = 0;

        for (IdentifiedCondition c : rated) {
            String reason = null;
            boolean counted = false;
            int shownRating = c.getEstimatedRating();

            if (readyScope && !inScopeIds.contains(c.getId())) {
                reason = "not-ready";
                notReadyCount++;
            } else if (isAbsorbed(c)) {
                String absorber = absorberName(c, survivors);
                if (absorber != null) {
                    reason = "pyramided-into:" + absorber;
                    notes.add(String.format(
                            "%s is rated inside your %s evaluation — VA won't pay it twice.",
                            c.getName(), absorber));
                } else {
                    // Group known but no surviving member (or no group recorded):
                    // still absorbed per the plan; label with the group when we
                    // have one so the exclusion is never unexplained.
                    String group = groupLabel(c);
                    reason = "pyramided-into:" + (group != null ? group : "another condition");
                    notes.add(String.format(
                            "%s is excluded by VA pyramiding rules (38 CFR §4.14) — "
                                    + "its symptoms are already rated under %s.",
                            c.getName(), group != null ? "your " + group + " evaluation"
                                    : "another evaluation"));
                }
            } else {
                counted = true;
                countedCount++;
                shownRating = effectiveRating(c);
                if (shownRating != c.getEstimatedRating()) {
                    notes.add(String.format(
                            "%s counts at %d%% — VA rates tinnitus at a single %d%% maximum "
                                    + "(38 CFR §4.87, code %s).",
                            c.getName(), PyramidingRules.TINNITUS_MAX,
                            PyramidingRules.TINNITUS_MAX, PyramidingRules.TINNITUS_CODE));
                }
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("conditionId", c.getId());
            row.put("name", c.getName());
            row.put("rating", shownRating);
            row.put("counted", counted);
            row.put("reason", reason);
            // Deterministic pyramid-group label (e.g. "Mental Health (§4.130)"),
            // present on BOTH the counted survivor and its absorbed members, so the
            // web folds them into one "Mental Health — 70%" line with the absorbed
            // members nested beneath. Resolved at read time from the VASRD code so it
            // is correct on existing claims. null when ungrouped (Jackson strips it).
            row.put("group", groupLabel(c));
            inputs.add(row);
        }

        for (List<Survivor> pair : bilateralPairs) {
            notes.add(String.format(
                    "%s and %s affect both sides of your body — the §4.26 bilateral factor "
                            + "adds 10%% of their combined value before the final rounding.",
                    pair.get(0).condition().getName(), pair.get(1).condition().getName()));
        }
        if (readyScope && notReadyCount > 0) {
            notes.add(String.format(
                    "%d condition%s not counted in this estimate — the evidence isn't yet "
                            + "strong on all three legs (diagnosis, in-service, nexus).",
                    notReadyCount, notReadyCount == 1 ? " is" : "s are"));
        }
        if (excludedCount > 0) {
            notes.add(String.format(
                    "%d condition%s you're not filing for %s excluded from this estimate.",
                    excludedCount, excludedCount == 1 ? "" : "s",
                    excludedCount == 1 ? "is" : "are"));
        }
        if (combinedRating > 0) {
            notes.add(String.format(
                    "Monthly estimate is the veteran-alone rate (no dependents) at %d VA "
                            + "compensation rates.", VaMathService.RATES_YEAR));
        }

        // Defensive consistency check between the plan (authoritative) and this
        // mirror's counted labels. Never veteran-visible; a warn is an operator
        // signal that PyramidingRules' rules changed without this mirror.
        int planCounted = plan.ratings().size() + plan.bilateralPairs().size() * 2;
        if (planCounted != countedCount) {
            log.warn("combined-rating input labels diverge from PyramidingRules for claim {}: "
                    + "plan counts {} conditions, mirror counted {}", claimId, planCounted, countedCount);
        }

        // The labeled sequential combine — one contributor per counted GROUP /
        // condition / bilateral pair, walked through the SAME §4.25 math via
        // VaMathService.combineLabeled. Built from the exact survivors/pairs the
        // authoritative math consumed; a divergence from `combinedRating` drops it
        // (never render a different number — the web keeps the abstract `steps`).
        List<Map<String, Object>> combineSteps =
                buildCombineSteps(inScope, survivors, bilateralPairs, combinedRating, claimId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scope", scope);
        body.put("combinedRating", combinedRating);
        body.put("monthlyEstimate", calc.get("monthly_estimate"));
        body.put("ratesYear", VaMathService.RATES_YEAR);
        body.put("notes", notes);
        body.put("steps", calc.get("steps"));
        if (combineSteps != null) {
            body.put("combineSteps", combineSteps);
        }
        body.put("inputs", inputs);
        body.put("excludedCount", (int) excludedCount);
        return body;
    }

    // -------------------------------------------------------------------------
    // Labeled sequential combine (combineSteps)
    // -------------------------------------------------------------------------

    /**
     * Fold the counted survivors + detected bilateral pairs into ordered labeled
     * contributors and walk them through {@link VaMathService#combineLabeled}
     * (the single §4.25 implementation). A pyramided GROUP becomes ONE contributor
     * (label = humanized group, rating = the surviving member's effective %, with
     * its absorbed members named); a bilateral pair becomes ONE contributor
     * ("<A> + <B> (bilateral)") at the §4.26 bilateral-combined value.
     *
     * <p><b>Correctness gate:</b> the last non-rounding step's cumulative and the
     * final rounded step MUST equal the authoritative {@code combinedRating}. On any
     * divergence we return {@code null} so the field is omitted (the web falls back
     * to the abstract {@code steps}) — a labeled walk that disagrees with the real
     * math is never shown. Empty (no counted contributors) → also {@code null}.
     */
    private List<Map<String, Object>> buildCombineSteps(
            List<IdentifiedCondition> inScope,
            List<Survivor> survivors, List<List<Survivor>> bilateralPairs,
            int combinedRating, Long claimId) {

        // IDs already consumed by a bilateral pair — excluded from group/singletons.
        Set<Long> pairedIds = new java.util.HashSet<>();
        for (List<Survivor> pair : bilateralPairs) {
            for (Survivor s : pair) {
                if (s.condition().getId() != null) pairedIds.add(s.condition().getId());
            }
        }

        List<VaMathService.LabeledRating> contributors = new ArrayList<>();

        // Pyramided groups: fold each group's counted survivors into ONE labeled
        // contributor (label = humanized group, rating = the group's max effective).
        // Absorbed members are named beneath (they don't add). Grouped survivors are
        // never bilateral in practice, but skip any that got paired to stay exact.
        Map<String, List<Survivor>> byGroup = new java.util.LinkedHashMap<>();
        List<Survivor> ungrouped = new ArrayList<>();
        for (Survivor s : survivors) {
            if (pairedIds.contains(s.condition().getId())) continue;
            String group = groupLabel(s.condition());
            if (group != null) {
                byGroup.computeIfAbsent(group, k -> new ArrayList<>()).add(s);
            } else {
                ungrouped.add(s);
            }
        }

        for (var entry : byGroup.entrySet()) {
            String group = entry.getKey();
            List<Survivor> members = entry.getValue();
            int rating = members.stream().mapToInt(Survivor::effective).max().orElse(0);
            List<String> absorbed = absorbedNamesForGroup(inScope, group);
            contributors.add(new VaMathService.LabeledRating(
                    titleCase(group), rating, absorbed));
        }

        for (Survivor s : ungrouped) {
            contributors.add(new VaMathService.LabeledRating(
                    s.condition().getName(), s.effective(), List.of()));
        }

        // Bilateral pairs → one contributor at the §4.26 bilateral-combined value,
        // computed EXACTLY as VaMathService folds a pair (combineTwo + 10%, rounded).
        for (List<Survivor> pair : bilateralPairs) {
            Survivor a = pair.get(0);
            Survivor b = pair.get(1);
            int combined = vaMathService.combineTwo(a.effective(), b.effective());
            int bilateralValue = (int) Math.round(combined + combined * 0.10);
            contributors.add(new VaMathService.LabeledRating(
                    a.condition().getName() + " + " + b.condition().getName() + " (bilateral)",
                    bilateralValue, List.of()));
        }

        if (contributors.isEmpty()) {
            return null; // no counted contributors — web keeps the abstract steps.
        }

        List<VaMathService.LabeledStep> steps = vaMathService.combineLabeled(contributors);

        // Correctness gate: the final rounded step must equal the authoritative
        // combinedRating, and the last pre-rounding cumulative must round to it too.
        VaMathService.LabeledStep last = steps.get(steps.size() - 1);
        VaMathService.LabeledStep lastContrib = steps.get(steps.size() - 2);
        int labeledRounded = (int) Math.round(last.combinedAfter());
        if (labeledRounded != combinedRating
                || vaMathService.vaRound(lastContrib.combinedAfter()) != combinedRating) {
            log.warn("combineSteps diverged from authoritative combined rating for claim {}: "
                    + "labeled walk landed on {} but VaMathService says {} — omitting combineSteps.",
                    claimId, labeledRounded, combinedRating);
            return null;
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (VaMathService.LabeledStep s : steps) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", s.label());
            row.put("rating", s.rating()); // null on the rounding step
            row.put("pointsAdded", s.pointsAdded());
            row.put("combinedAfter", s.combinedAfter());
            row.put("remainingAfter", s.remainingAfter());
            row.put("absorbedMembers", s.absorbedMembers());
            row.put("rounding", s.rounding());
            out.add(row);
        }
        return out;
    }

    /**
     * Absorbed member names for a pyramid group — the pyramided-into conditions
     * that are rated together and DON'T add. Recomputed from `inScope` using the
     * same deterministic {@link #groupLabel} so members attach to the right group
     * regardless of inputs-row order or stale stored group strings.
     */
    private List<String> absorbedNamesForGroup(
            List<IdentifiedCondition> inScope, String groupLabel) {
        return inScope.stream()
                .filter(RatingController::isAbsorbed)
                .filter(c -> groupLabel.equals(groupLabel(c)))
                .map(IdentifiedCondition::getName)
                .toList();
    }

    /** "mental health" → "Mental health" (first-letter cap; leaves the rest intact). */
    private static String titleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // -------------------------------------------------------------------------
    // Claim resolution
    // -------------------------------------------------------------------------

    /**
     * Resolve the target claim id via the existing chokepoint.
     *
     * <p>{@code X-View-As} present → {@link ClaimAccessService#resolveIfPresent}
     * (400/403/404 exactly like every other claim endpoint) + strict
     * {@code VIEW_ANALYSIS} scope — the combined rating IS analysis output.
     * Header absent → the caller's own latest claim; {@code null} (→ 200
     * zero-state) when they have none. Never auto-creates: a read-only rating
     * probe must not write a claim row.
     */
    private Long resolveClaimId(User user, HttpServletRequest request) {
        Optional<ClaimAccess> viewAs = claimAccessService.resolveIfPresent(user, request);
        if (viewAs.isPresent()) {
            ClaimAccess access = viewAs.get();
            claimAccessService.assertScope(access, AccessScope.VIEW_ANALYSIS, user);
            return access.claimId();
        }
        List<Claim> claims = claimRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        return claims.isEmpty() ? null : claims.get(0).getId();
    }

    // -------------------------------------------------------------------------
    // Classification mirrors of PyramidingRules (labels only — never the math)
    // -------------------------------------------------------------------------

    /** Mirror of the plan's absorption rule: any non-blank pyramidReason drops the row. */
    private static boolean isAbsorbed(IdentifiedCondition c) {
        String reason = c.getPyramidReason();
        return reason != null && !reason.isBlank();
    }

    /** Mirror of the plan's tinnitus cap (§4.87 / VASRD 6260 → max 10%). */
    private static int effectiveRating(IdentifiedCondition c) {
        int rating = c.getEstimatedRating();
        if (PyramidingRules.TINNITUS_CODE.equals(c.getVasrdCode())
                && rating > PyramidingRules.TINNITUS_MAX) {
            return PyramidingRules.TINNITUS_MAX;
        }
        return rating;
    }

    /**
     * The absorbing condition's name for a pyramid-absorbed row: the
     * highest-effective-rated SURVIVOR sharing its (deterministic) pyramid group —
     * that's the evaluation whose criteria already covers the absorbed symptoms.
     * {@code null} when the row has no group, no group member survived, or the only
     * candidate is the row itself (never "MDD is rated inside your MDD evaluation").
     */
    private String absorberName(IdentifiedCondition absorbed, List<Survivor> survivors) {
        String group = groupLabel(absorbed);
        if (group == null) return null;
        return survivors.stream()
                .filter(s -> group.equals(groupLabel(s.condition())))
                .filter(s -> !java.util.Objects.equals(s.condition().getId(), absorbed.getId()))
                .max(Comparator.comparingInt(Survivor::effective))
                .map(s -> s.condition().getName())
                .filter(name -> name != null && !name.equalsIgnoreCase(absorbed.getName()))
                .orElse(null);
    }

    /**
     * The veteran-facing pyramiding-group label for a condition, resolved at READ
     * time so existing claims render correctly without a re-analysis. The
     * deterministic {@link PyramidingGroups} map (VASRD code → §4.130 mental-health,
     * etc.) is the source of truth — it fixes stale / empty / self-referential
     * LLM-set {@code pyramidGroup} values on already-analyzed claims. Only when the
     * code is UNMAPPED do we fall back to any stored group string, so a non-curated
     * absorption (e.g. musculoskeletal) the LLM grouped still gets a label rather
     * than none. {@code null} ⇒ ungrouped (flat row).
     */
    private String groupLabel(IdentifiedCondition c) {
        String deterministic = pyramidingGroups.groupFor(c.getVasrdCode()).orElse(null);
        if (deterministic != null) return deterministic;
        return humanizedGroupStored(c);
    }

    /** Stored-group fallback: "mental_health" → "mental health"; null/blank → null. */
    private static String humanizedGroupStored(IdentifiedCondition c) {
        String group = c.getPyramidGroup();
        if (group == null || group.isBlank()) return null;
        return group.trim().replace('_', ' ');
    }

    // -------------------------------------------------------------------------
    // Readiness (scope=ready)
    // -------------------------------------------------------------------------

    /**
     * All three evidence legs STRONG — the same normalization as
     * {@code ConditionGenerationService.legStatus} and the web's
     * {@code toTriadLevel} (case-insensitive; absent leg/status ⇒ not strong).
     */
    private static boolean allLegsStrong(IdentifiedCondition c) {
        return legStrong(c.getTriadDiagnosis())
                && legStrong(c.getTriadInService())
                && legStrong(c.getTriadNexus());
    }

    private static boolean legStrong(Map<String, Object> leg) {
        Object status = leg == null ? null : leg.get("status");
        return status != null
                && "STRONG".equals(status.toString().trim().toUpperCase(Locale.ROOT));
    }

    // -------------------------------------------------------------------------
    // Zero-state
    // -------------------------------------------------------------------------

    private static Map<String, Object> zeroBody(String scope) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scope", scope);
        body.put("combinedRating", 0);
        body.put("monthlyEstimate", 0.0);
        body.put("ratesYear", VaMathService.RATES_YEAR);
        body.put("notes", List.of());
        body.put("steps", List.of());
        body.put("inputs", List.of());
        body.put("excludedCount", 0);
        return body;
    }
}
