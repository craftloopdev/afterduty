package com.afterduty.controller;

import com.afterduty.model.Claim;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.model.User;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.ConditionRepository;
import com.afterduty.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C1 contract test — GET /api/claim/combined-rating (report P1-1).
 *
 * <p>Pins the PINNED CONTRACT end to end through the REAL PyramidingRules +
 * VaMathService beans: pyramid-absorbed conditions must NOT combine (with a
 * human-readable "won't pay it twice" note), bilateral pairs MUST get the
 * §4.26 +10% factor, tinnitus is capped at 10% (§4.87 / 6260), scope=ready
 * only counts all-three-legs-strong conditions, and the empty claim / no-claim
 * cases return a 200 zero-state rather than 404. Every asserted number below
 * is hand-computed from §4.25/§4.26 — if the endpoint drifts from VA math,
 * this fails loudly.
 */
@SpringBootTest
@ActiveProfiles("local")
@Tag("regression")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ratingctrltest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.cloud.gcp.sql.enabled=false",
        "spring.autoconfigure.exclude=com.google.cloud.spring.autoconfigure.sql.GcpCloudSqlAutoConfiguration,com.google.cloud.spring.autoconfigure.firestore.GcpFirestoreAutoConfiguration,com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration,com.google.cloud.spring.autoconfigure.secretmanager.GcpSecretManagerAutoConfiguration"
})
class RatingControllerTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    OncePerRequestFilter authFilter;

    @Autowired
    ClaimRepository claimRepository;

    @Autowired
    ConditionRepository conditionRepository;

    @Autowired
    UserRepository userRepository;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(authFilter)
                .build();
    }

    /** Auto-creates the user + claim via the API and returns the claim id. */
    private long claimFor(String email) throws Exception {
        mvc.perform(get("/api/claim").header("X-User-Email", email))
                .andExpect(status().isOk());
        User u = userRepository.findByEmail(email.toLowerCase())
                .or(() -> userRepository.findByEmail(email))
                .orElseThrow();
        return claimRepository.findByUserIdOrderByCreatedAtDesc(u.getId()).get(0).getId();
    }

    private static Map<String, Object> leg(String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("evidence", List.of());
        return m;
    }

    /** Save an active condition; allStrong=false leaves the nexus leg WEAK (not ready). */
    private IdentifiedCondition saveCondition(long claimId, String name, String vasrdCode,
                                              int rating, boolean allStrong,
                                              String pyramidGroup, String pyramidReason) {
        IdentifiedCondition c = IdentifiedCondition.builder()
                .claimId(claimId)
                .name(name)
                .vasrdCode(vasrdCode)
                .estimatedRating(rating)
                .triadDiagnosis(leg("STRONG"))
                .triadInService(leg("STRONG"))
                .triadNexus(leg(allStrong ? "STRONG" : "WEAK"))
                .build();
        c.setPyramidGroup(pyramidGroup);
        c.setPyramidReason(pyramidReason);
        return conditionRepository.save(c);
    }

    /**
     * The report's own P1-1 scenario: PTSD 70% with an absorbed anxiety 50%
     * (must NOT combine) plus two knees sharing DC 5260 (MUST get §4.26).
     *
     * Hand-computed: knees 20⊕10 = 28, +10% = 2.8 → bilateral value 31 as one
     * disability. 70 then 31: 100−70=30 remaining, 31% of 30 = 9.3 → exact
     * 79.3 → VA-rounds to 80%. 2026 veteran-alone 80% = $2,102.15. The naive
     * (pre-C1) math would have combined 70+50+20+10 → 84.88 → 80... with
     * anxiety inflating intermediate math and no bilateral note; the split
     * assertions on inputs pin the classification, not just the total.
     */
    @Test
    void scopeAll_absorbsPyramidedCondition_andAppliesBilateralFactor() throws Exception {
        String email = "rating-all@example.com";
        long claimId = claimFor(email);
        saveCondition(claimId, "PTSD", "9411", 70, true, "mental_health", null);
        saveCondition(claimId, "Generalized Anxiety Disorder", "9400", 50, true, "mental_health",
                "Mental health conditions are rated under a single evaluation (38 CFR § 4.14, § 4.130)");
        saveCondition(claimId, "Left knee limitation of flexion", "5260", 20, false, null, null);
        saveCondition(claimId, "Right knee limitation of flexion", "5260", 10, false, null, null);

        mvc.perform(get("/api/claim/combined-rating").param("scope", "all")
                        .header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("all"))
                .andExpect(jsonPath("$.combinedRating").value(80))
                .andExpect(jsonPath("$.monthlyEstimate").value(2102.15))
                .andExpect(jsonPath("$.ratesYear").value(2026))
                // inputs — one row per active rated condition, in save order.
                .andExpect(jsonPath("$.inputs", hasSize(4)))
                .andExpect(jsonPath("$.inputs[0].name").value("PTSD"))
                .andExpect(jsonPath("$.inputs[0].rating").value(70))
                .andExpect(jsonPath("$.inputs[0].counted").value(true))
                // App-wide Jackson non_null inclusion strips null map values, so a
                // counted row carries NO reason key (JS reads undefined ≙ null).
                .andExpect(jsonPath("$.inputs[0].reason").doesNotExist())
                .andExpect(jsonPath("$.inputs[1].name").value("Generalized Anxiety Disorder"))
                .andExpect(jsonPath("$.inputs[1].counted").value(false))
                .andExpect(jsonPath("$.inputs[1].reason").value("pyramided-into:PTSD"))
                .andExpect(jsonPath("$.inputs[2].counted").value(true))
                .andExpect(jsonPath("$.inputs[3].counted").value(true))
                // group — the COUNTED mental-health survivor (PTSD) and its ABSORBED
                // member (Anxiety) both carry the DETERMINISTIC §4.130 group label
                // (resolved from the VASRD code at read time) so the web nests them.
                .andExpect(jsonPath("$.inputs[0].group").value("Mental Health (§4.130)"))
                .andExpect(jsonPath("$.inputs[1].group").value("Mental Health (§4.130)"))
                // ungrouped counted knees have NO group key (Jackson non_null strips null).
                .andExpect(jsonPath("$.inputs[2].group").doesNotExist())
                .andExpect(jsonPath("$.inputs[3].group").doesNotExist())
                // nothing excluded here.
                .andExpect(jsonPath("$.excludedCount").value(0))
                // notes — human-readable absorption + bilateral + assumption.
                .andExpect(jsonPath("$.notes", hasItem(containsString(
                        "Generalized Anxiety Disorder is rated inside your PTSD evaluation"))))
                .andExpect(jsonPath("$.notes", hasItem(containsString("won't pay it twice"))))
                .andExpect(jsonPath("$.notes", hasItem(containsString("bilateral factor"))))
                .andExpect(jsonPath("$.notes", hasItem(containsString("no dependents"))))
                .andExpect(jsonPath("$.notes", hasItem(containsString("2026"))))
                // steps — VaMathService's deterministic combination walk.
                .andExpect(jsonPath("$.steps", not(empty())))
                .andExpect(jsonPath("$.steps", hasItem(containsString("Bilateral pair 20% + 10%"))))
                .andExpect(jsonPath("$.steps", hasItem(containsString("Apply 70%"))));
    }

    /**
     * scope=ready — only all-three-legs-strong conditions combine. The knees'
     * nexus leg is WEAK, so only PTSD (70%) counts: $1,808.45 at 2026 rates.
     * The ready-scope absorbed anxiety keeps its pyramiding reason (it IS
     * ready — it just doesn't pay), and the knees get reason "not-ready".
     */
    @Test
    void scopeReady_countsOnlyAllLegsStrongConditions() throws Exception {
        String email = "rating-ready@example.com";
        long claimId = claimFor(email);
        saveCondition(claimId, "PTSD", "9411", 70, true, "mental_health", null);
        saveCondition(claimId, "Generalized Anxiety Disorder", "9400", 50, true, "mental_health",
                "Mental health conditions are rated under a single evaluation (38 CFR § 4.14, § 4.130)");
        saveCondition(claimId, "Left knee limitation of flexion", "5260", 20, false, null, null);
        saveCondition(claimId, "Right knee limitation of flexion", "5260", 10, false, null, null);

        mvc.perform(get("/api/claim/combined-rating").param("scope", "ready")
                        .header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("ready"))
                .andExpect(jsonPath("$.combinedRating").value(70))
                .andExpect(jsonPath("$.monthlyEstimate").value(1808.45))
                .andExpect(jsonPath("$.ratesYear").value(2026))
                .andExpect(jsonPath("$.inputs", hasSize(4)))
                .andExpect(jsonPath("$.inputs[0].counted").value(true))
                .andExpect(jsonPath("$.inputs[1].counted").value(false))
                .andExpect(jsonPath("$.inputs[1].reason").value("pyramided-into:PTSD"))
                .andExpect(jsonPath("$.inputs[2].counted").value(false))
                .andExpect(jsonPath("$.inputs[2].reason").value("not-ready"))
                .andExpect(jsonPath("$.inputs[3].counted").value(false))
                .andExpect(jsonPath("$.inputs[3].reason").value("not-ready"))
                .andExpect(jsonPath("$.notes", hasItem(containsString(
                        "2 conditions are not counted in this estimate"))))
                // No bilateral note in ready scope — the pair fell out of scope.
                .andExpect(jsonPath("$.notes", not(hasItem(containsString("bilateral")))));
    }

    /** §4.87 / DC 6260 — tinnitus above 10% is capped, with a note, and pays the 10% rate. */
    @Test
    void tinnitusCappedAtTenPercent() throws Exception {
        String email = "rating-tinnitus@example.com";
        long claimId = claimFor(email);
        saveCondition(claimId, "Tinnitus", "6260", 20, true, null, null);

        mvc.perform(get("/api/claim/combined-rating").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("all")) // default scope
                .andExpect(jsonPath("$.combinedRating").value(10))
                .andExpect(jsonPath("$.monthlyEstimate").value(180.42))
                .andExpect(jsonPath("$.inputs", hasSize(1)))
                .andExpect(jsonPath("$.inputs[0].counted").value(true))
                // The rating shown is the EFFECTIVE one that entered the math.
                .andExpect(jsonPath("$.inputs[0].rating").value(10))
                .andExpect(jsonPath("$.notes", hasItem(containsString("single 10% maximum"))));
    }

    /** Claim exists but has no conditions → 200 zero-state (never 404). */
    @Test
    void emptyClaim_returns200ZeroState() throws Exception {
        String email = "rating-empty@example.com";
        claimFor(email);

        mvc.perform(get("/api/claim/combined-rating").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.combinedRating").value(0))
                .andExpect(jsonPath("$.monthlyEstimate").value(0.0))
                .andExpect(jsonPath("$.ratesYear").value(2026))
                .andExpect(jsonPath("$.inputs", empty()))
                .andExpect(jsonPath("$.steps", empty()))
                .andExpect(jsonPath("$.notes", empty()));
    }

    /** User with NO claim at all → 200 zero-state; the GET must not auto-create a claim. */
    @Test
    void noClaim_returns200ZeroState_withoutCreatingClaim() throws Exception {
        String email = "rating-noclaim@example.com";

        mvc.perform(get("/api/claim/combined-rating").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.combinedRating").value(0))
                .andExpect(jsonPath("$.inputs", empty()));

        User u = userRepository.findByEmail(email.toLowerCase())
                .or(() -> userRepository.findByEmail(email))
                .orElseThrow();
        org.junit.jupiter.api.Assertions.assertTrue(
                claimRepository.findByUserIdOrderByCreatedAtDesc(u.getId()).isEmpty(),
                "read-only rating endpoint must not auto-create a claim");
    }

    /**
     * "Don't include in my claim" (excludedFromClaim) — an excluded condition
     * drops OUT of the combination and out of `inputs`, exactly like a
     * superseded row but reversible. Three conditions (70/50/30); exclude the
     * 30% → combined falls from the full three-way combine to the 70⊕50 result.
     *
     * Hand-computed: 70,50,30 → 70; 100−70=30, 50% of 30 = 15 → 85; 100−85=15,
     * 30% of 15 = 4.5 → 89.5 → VA-rounds to 90%. Excluding the 30% leaves 70⊕50 =
     * 85 → rounds to 90 as well at this pairing, so use ratings that MOVE the
     * headline: 60/40/40. Full: 60→ +40%of40=16 →76 → +40%of24=9.6 →85.6 →90.
     * Exclude one 40: 60⊕40 = 76 → rounds to 80. The exclusion clearly drops it.
     */
    @Test
    void excludedFromClaim_dropsOutOfCombinedRating() throws Exception {
        String email = "rating-excluded@example.com";
        long claimId = claimFor(email);
        saveCondition(claimId, "Condition A", "5000", 60, true, null, null);
        saveCondition(claimId, "Condition B", "5001", 40, true, null, null);
        IdentifiedCondition excludable = saveCondition(claimId, "Condition C", "5002", 40, true, null, null);

        // Before exclusion: all three combine → 90%.
        mvc.perform(get("/api/claim/combined-rating").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.combinedRating").value(90))
                .andExpect(jsonPath("$.inputs", hasSize(3)));

        // Exclude Condition C.
        excludable.setExcludedFromClaim(true);
        conditionRepository.save(excludable);

        // After exclusion: only A⊕B count → 80%, and C is gone from inputs entirely.
        mvc.perform(get("/api/claim/combined-rating").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.combinedRating").value(80))
                .andExpect(jsonPath("$.inputs", hasSize(2)))
                .andExpect(jsonPath("$.inputs[*].name", not(hasItem("Condition C"))));

        // Toggling back re-includes it → 90% again (reversible).
        excludable.setExcludedFromClaim(false);
        conditionRepository.save(excludable);
        mvc.perform(get("/api/claim/combined-rating").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.combinedRating").value(90))
                .andExpect(jsonPath("$.inputs", hasSize(3)));
    }

    /** Excluding applies to scope=ready too (both scopes share the same filter). */
    @Test
    void excludedFromClaim_dropsOutOfReadyScope() throws Exception {
        String email = "rating-excluded-ready@example.com";
        long claimId = claimFor(email);
        saveCondition(claimId, "Ready A", "5000", 60, true, null, null);
        IdentifiedCondition excludable = saveCondition(claimId, "Ready B", "5001", 40, true, null, null);
        excludable.setExcludedFromClaim(true);
        conditionRepository.save(excludable);

        mvc.perform(get("/api/claim/combined-rating").param("scope", "ready")
                        .header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.combinedRating").value(60))
                .andExpect(jsonPath("$.inputs", hasSize(1)))
                .andExpect(jsonPath("$.inputs[0].name").value("Ready A"));
    }

    /**
     * excludedCount reflects the ACTIVE, rated conditions the veteran left out
     * ("Don't include in my claim"), with a matching human note (singular/plural),
     * while a superseded or unrated row is NOT counted (only the base query rows
     * that the exclude filter dropped are). Two counted (60/40) + two excluded.
     */
    @Test
    void excludedCount_reflectsExcludedConditions_withNote() throws Exception {
        String email = "rating-excount@example.com";
        long claimId = claimFor(email);
        saveCondition(claimId, "Counted A", "5000", 60, true, null, null);
        saveCondition(claimId, "Counted B", "5001", 40, true, null, null);
        IdentifiedCondition ex1 = saveCondition(claimId, "Excluded X", "5002", 30, true, null, null);
        IdentifiedCondition ex2 = saveCondition(claimId, "Excluded Y", "5003", 20, true, null, null);
        // A superseded excluded row must NOT be counted — it's not in the base query.
        IdentifiedCondition supersededExcluded = saveCondition(claimId, "Old excluded", "5004", 50, true, null, null);
        supersededExcluded.setSupersededBy(ex1.getId());
        supersededExcluded.setExcludedFromClaim(true);
        conditionRepository.save(supersededExcluded);
        ex1.setExcludedFromClaim(true);
        ex2.setExcludedFromClaim(true);
        conditionRepository.save(ex1);
        conditionRepository.save(ex2);

        // Two excluded → plural note, excludedCount 2, and neither is in inputs.
        mvc.perform(get("/api/claim/combined-rating").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excludedCount").value(2))
                .andExpect(jsonPath("$.inputs", hasSize(2)))
                .andExpect(jsonPath("$.inputs[*].name", not(hasItem("Excluded X"))))
                .andExpect(jsonPath("$.inputs[*].name", not(hasItem("Excluded Y"))))
                .andExpect(jsonPath("$.notes", hasItem(containsString(
                        "2 conditions you're not filing for are excluded from this estimate"))));

        // Re-include one → singular note, excludedCount 1.
        ex2.setExcludedFromClaim(false);
        conditionRepository.save(ex2);
        mvc.perform(get("/api/claim/combined-rating").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excludedCount").value(1))
                .andExpect(jsonPath("$.notes", hasItem(containsString(
                        "1 condition you're not filing for is excluded from this estimate"))));

        // None excluded → count 0, no excluded note.
        ex1.setExcludedFromClaim(false);
        conditionRepository.save(ex1);
        mvc.perform(get("/api/claim/combined-rating").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.excludedCount").value(0))
                .andExpect(jsonPath("$.notes", not(hasItem(containsString(
                        "not filing for")))));
    }

    /**
     * A counted mental-health survivor carries its deterministic `group`, and an
     * ABSORBED member of the same group also carries `group` (plus its
     * pyramided-into reason) so the web can nest it under the group heading.
     */
    @Test
    void countedAndAbsorbedConditions_carryHumanizedGroup() throws Exception {
        String email = "rating-group@example.com";
        long claimId = claimFor(email);
        saveCondition(claimId, "PTSD", "9411", 70, true, "mental_health", null);
        saveCondition(claimId, "Major Depressive Disorder", "9434", 50, true, "mental_health",
                "Mental health conditions are rated under a single evaluation (38 CFR § 4.14, § 4.130)");

        mvc.perform(get("/api/claim/combined-rating").header("X-User-Email", email))
                .andExpect(status().isOk())
                // counted survivor: group present, no reason.
                .andExpect(jsonPath("$.inputs[0].name").value("PTSD"))
                .andExpect(jsonPath("$.inputs[0].counted").value(true))
                .andExpect(jsonPath("$.inputs[0].group").value("Mental Health (§4.130)"))
                .andExpect(jsonPath("$.inputs[0].reason").doesNotExist())
                // absorbed member: group present AND pyramided-into reason.
                .andExpect(jsonPath("$.inputs[1].name").value("Major Depressive Disorder"))
                .andExpect(jsonPath("$.inputs[1].counted").value(false))
                .andExpect(jsonPath("$.inputs[1].group").value("Mental Health (§4.130)"))
                .andExpect(jsonPath("$.inputs[1].reason").value("pyramided-into:PTSD"));
    }

    /**
     * THE bug this fix targets (task #186): an EXISTING claim whose mental-health
     * conditions were absorbed by the LLM (pyramidReason set) but never got a
     * deterministic {@code pyramidGroup} stamped (the assignPyramidingGroups pass
     * only runs on re-analysis). Before this fix the survivor rendered as a lone
     * "PTSD" row instead of the group. The group label is now resolved at READ time
     * from the VASRD code, so the veteran sees the grouped "Mental Health (§4.130)"
     * line — its members named — WITHOUT re-running analysis.
     */
    @Test
    void staleOrNullStoredGroup_stillGroupsDeterministicallyAtReadTime() throws Exception {
        String email = "rating-stalegroup@example.com";
        long claimId = claimFor(email);
        // No stored pyramidGroup on EITHER row (null) — the stale-data shape.
        saveCondition(claimId, "PTSD", "9411", 70, false, null, null);
        saveCondition(claimId, "Major Depressive Disorder", "9434", 50, false, null,
                "Mental health conditions are rated under a single evaluation (38 CFR § 4.14, § 4.130)");

        mvc.perform(get("/api/claim/combined-rating").param("scope", "all")
                        .header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.combinedRating").value(70))
                // Deterministic §4.130 group resolved from the code, not the (null) stored value.
                .andExpect(jsonPath("$.inputs[0].name").value("PTSD"))
                .andExpect(jsonPath("$.inputs[0].group").value("Mental Health (§4.130)"))
                .andExpect(jsonPath("$.inputs[1].name").value("Major Depressive Disorder"))
                .andExpect(jsonPath("$.inputs[1].group").value("Mental Health (§4.130)"))
                // The absorber note names the real survivor (PTSD), never itself.
                .andExpect(jsonPath("$.inputs[1].reason").value("pyramided-into:PTSD"))
                .andExpect(jsonPath("$.notes", hasItem(containsString(
                        "Major Depressive Disorder is rated inside your PTSD evaluation"))))
                // combineSteps: ONE grouped contributor labelled §4.130, naming its member.
                .andExpect(jsonPath("$.combineSteps[0].label").value("Mental Health (§4.130)"))
                .andExpect(jsonPath("$.combineSteps[0].rating").value(70))
                .andExpect(jsonPath("$.combineSteps[0].absorbedMembers",
                        hasItem("Major Depressive Disorder")));
    }

    /**
     * Fallback path: a non-curated absorption (a VASRD code {@link PyramidingGroups}
     * doesn't map) still shows whatever group the pipeline stored, so the exclusion
     * is never unexplained. Deterministic grouping only OVERRIDES mapped codes.
     */
    @Test
    void unmappedCode_fallsBackToStoredGroupLabel() throws Exception {
        String email = "rating-unmapped@example.com";
        long claimId = claimFor(email);
        // 7200-family (digestive) is deliberately NOT in the curated PyramidingGroups map.
        saveCondition(claimId, "Primary digestive", "7200", 60, false, "digestive_system", null);
        saveCondition(claimId, "Absorbed digestive", "7200-1", 30, false, "digestive_system",
                "Rated together under one digestive evaluation");

        mvc.perform(get("/api/claim/combined-rating").param("scope", "all")
                        .header("X-User-Email", email))
                .andExpect(status().isOk())
                // Stored group survives (humanized), since the code is unmapped.
                .andExpect(jsonPath("$.inputs[0].group").value("digestive system"))
                .andExpect(jsonPath("$.inputs[1].group").value("digestive system"));
    }

    // --- combineSteps: the labeled sequential VA-math combine (#185) ---

    /**
     * combineSteps is ordered DESCENDING, each step's running combined/remaining is
     * correct, a pyramid GROUP folds into ONE labeled step (naming its absorbed
     * members), a bilateral pair folds into ONE labeled "(bilateral)" step, and the
     * final rounding step equals the authoritative combinedRating.
     *
     * Same scenario as the P1-1 test: PTSD 70 (mental-health group, absorbing
     * Anxiety 50) + two knees 20/10 (bilateral → value 31). Contributors sorted
     * descending: Mental health 70, then the bilateral 31. 70 → combined 70, 30
     * left; 31% of 30 = 9.3 → combined 79.3, 20.7 left → rounds to 80.
     */
    @Test
    void combineSteps_labeledSequentialCombine_groupAndBilateral() throws Exception {
        String email = "rating-combine@example.com";
        long claimId = claimFor(email);
        saveCondition(claimId, "PTSD", "9411", 70, false, "mental_health", null);
        saveCondition(claimId, "Generalized Anxiety Disorder", "9400", 50, false, "mental_health",
                "Mental health conditions are rated under a single evaluation (38 CFR § 4.14, § 4.130)");
        saveCondition(claimId, "Left knee limitation of flexion", "5260", 20, false, null, null);
        saveCondition(claimId, "Right knee limitation of flexion", "5260", 10, false, null, null);

        mvc.perform(get("/api/claim/combined-rating").param("scope", "all")
                        .header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.combinedRating").value(80))
                // Three steps: mental-health group, bilateral pair, final rounding.
                .andExpect(jsonPath("$.combineSteps", hasSize(3)))
                // Step 0 — highest contributor first (descending). The label is the
                // deterministic §4.130 group resolved from the VASRD code.
                .andExpect(jsonPath("$.combineSteps[0].label").value("Mental Health (§4.130)"))
                .andExpect(jsonPath("$.combineSteps[0].rating").value(70))
                .andExpect(jsonPath("$.combineSteps[0].pointsAdded").value(70.0))
                .andExpect(jsonPath("$.combineSteps[0].combinedAfter").value(70.0))
                .andExpect(jsonPath("$.combineSteps[0].remainingAfter").value(30.0))
                .andExpect(jsonPath("$.combineSteps[0].rounding").value(false))
                // The group step NAMES its absorbed members (they don't add).
                .andExpect(jsonPath("$.combineSteps[0].absorbedMembers",
                        hasItem("Generalized Anxiety Disorder")))
                // Step 1 — the bilateral pair as ONE labeled contributor at value 31.
                .andExpect(jsonPath("$.combineSteps[1].label",
                        containsString("(bilateral)")))
                .andExpect(jsonPath("$.combineSteps[1].label",
                        containsString("Left knee limitation of flexion")))
                .andExpect(jsonPath("$.combineSteps[1].rating").value(31))
                .andExpect(jsonPath("$.combineSteps[1].pointsAdded").value(9.3))
                .andExpect(jsonPath("$.combineSteps[1].combinedAfter").value(79.3))
                // Final rounding step — rating null, equals the authoritative rating.
                .andExpect(jsonPath("$.combineSteps[2].rounding").value(true))
                .andExpect(jsonPath("$.combineSteps[2].rating").doesNotExist())
                .andExpect(jsonPath("$.combineSteps[2].label").value("Rounded to nearest 10"))
                .andExpect(jsonPath("$.combineSteps[2].combinedAfter").value(80.0))
                // The abstract steps[] are UNCHANGED (kept alongside combineSteps).
                .andExpect(jsonPath("$.steps", not(empty())))
                // excludedCount unaffected by the new field.
                .andExpect(jsonPath("$.excludedCount").value(0));
    }

    /**
     * The last non-rounding combineSteps cumulative + the final rounded step BOTH
     * equal the authoritative combinedRating (correctness gate). Three ungrouped
     * conditions 60/40/40 → 60 → +16 → 76 → +9.6 → 85.6 → rounds to 90.
     */
    @Test
    void combineSteps_finalEqualsAuthoritativeCombinedRating() throws Exception {
        String email = "rating-combine-gate@example.com";
        long claimId = claimFor(email);
        saveCondition(claimId, "Condition A", "5000", 60, false, null, null);
        saveCondition(claimId, "Condition B", "5001", 40, false, null, null);
        saveCondition(claimId, "Condition C", "5002", 40, false, null, null);

        mvc.perform(get("/api/claim/combined-rating").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.combinedRating").value(90))
                .andExpect(jsonPath("$.combineSteps", hasSize(4)))
                .andExpect(jsonPath("$.combineSteps[0].rating").value(60))
                .andExpect(jsonPath("$.combineSteps[0].combinedAfter").value(60.0))
                .andExpect(jsonPath("$.combineSteps[1].combinedAfter").value(76.0))
                .andExpect(jsonPath("$.combineSteps[2].combinedAfter").value(85.6))
                // Last contributor's cumulative (85.6) rounds to the final rating.
                .andExpect(jsonPath("$.combineSteps[3].rounding").value(true))
                .andExpect(jsonPath("$.combineSteps[3].combinedAfter").value(90.0));
    }

    /** No counted contributors (empty claim) → combineSteps omitted; web keeps steps[]. */
    @Test
    void combineSteps_omittedWhenNoContributors() throws Exception {
        String email = "rating-combine-empty@example.com";
        claimFor(email);

        mvc.perform(get("/api/claim/combined-rating").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.combinedRating").value(0))
                .andExpect(jsonPath("$.combineSteps").doesNotExist());
    }

    /** Superseded (prior-generation) rows must never enter the combination. */
    @Test
    void supersededConditionExcluded() throws Exception {
        String email = "rating-superseded@example.com";
        long claimId = claimFor(email);
        IdentifiedCondition active = saveCondition(claimId, "PTSD", "9411", 50, true, null, null);
        IdentifiedCondition old = saveCondition(claimId, "PTSD (old gen)", "9411", 70, true, null, null);
        old.setSupersededBy(active.getId());
        conditionRepository.save(old);

        mvc.perform(get("/api/claim/combined-rating").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.combinedRating").value(50))
                .andExpect(jsonPath("$.monthlyEstimate").value(1132.90))
                .andExpect(jsonPath("$.inputs", hasSize(1)))
                .andExpect(jsonPath("$.inputs[0].name").value("PTSD"));
    }

    // --- Authz ---

    @Test
    void unauthenticatedRequest_is401() throws Exception {
        mvc.perform(get("/api/claim/combined-rating"))
                .andExpect(status().isUnauthorized());
    }

    /** X-View-As against a claim the caller has no accepted share for → 403 via the chokepoint. */
    @Test
    void viewAsWithoutShare_is403() throws Exception {
        long victimClaimId = claimFor("rating-victim@example.com");
        claimFor("rating-attacker@example.com");

        mvc.perform(get("/api/claim/combined-rating")
                        .header("X-User-Email", "rating-attacker@example.com")
                        .header("X-View-As", String.valueOf(victimClaimId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidScope_is400() throws Exception {
        String email = "rating-badscope@example.com";
        claimFor(email);

        mvc.perform(get("/api/claim/combined-rating").param("scope", "everything")
                        .header("X-User-Email", email))
                .andExpect(status().isBadRequest());
    }
}
