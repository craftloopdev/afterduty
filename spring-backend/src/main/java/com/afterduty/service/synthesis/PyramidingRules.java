package com.afterduty.service.synthesis;

import com.afterduty.model.IdentifiedCondition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Applies VA pyramiding rules to a set of identified conditions before they
 * are handed to {@link com.afterduty.service.VaMathService} for the
 * combined-rating calculation.
 *
 * <p>This layer covers the three highest-impact §4.14 / §4.26 / §4.87 cases
 * for v1:
 * <ol>
 *   <li><b>Pyramidically-absorbed conditions excluded.</b> When the
 *       upstream Claude/Gemini synthesis has flagged a condition with
 *       {@code pyramidReason} (e.g. an anxiety diagnosis absorbed into a
 *       PTSD evaluation under §4.130), that condition's rating is no
 *       longer combined — its criteria is already rolled up in the
 *       absorbing condition.</li>
 *   <li><b>Tinnitus capped at 10%.</b> §4.87 / VASRD 6260 — tinnitus is
 *       evaluated under a single 10% maximum regardless of severity or
 *       laterality. If a condition with VASRD code {@code 6260} carries a
 *       higher rating, it is clamped.</li>
 *   <li><b>Bilateral pairs detected.</b> §4.26 — when exactly two
 *       conditions share a diagnostic code (the canonical case: a paired-
 *       organ injury, e.g. two knees both rated 5260), they are treated as
 *       a bilateral pair so {@link com.afterduty.service.VaMathService}
 *       can apply the +10% bilateral factor.</li>
 * </ol>
 *
 * <p>Returned via {@link Plan} so the caller can also surface
 * human-readable {@code notes} to the UI, explaining why a condition was
 * dropped or capped — pyramiding is otherwise opaque to veterans.
 */
@Component
public class PyramidingRules {

    /** VASRD diagnostic code for tinnitus (capped at 10%). */
    public static final String TINNITUS_CODE = "6260";
    public static final int TINNITUS_MAX = 10;

    public record Plan(
            List<Integer> ratings,
            List<int[]> bilateralPairs,
            List<String> notes
    ) {}

    /**
     * Reduce a raw list of {@link IdentifiedCondition} into the integer
     * ratings + bilateral-pair structure {@code VaMathService.calculateCombinedRating}
     * expects. Caller is responsible for persisting any rating mutations
     * (currently only tinnitus) — this method does NOT mutate the input.
     */
    public Plan plan(List<IdentifiedCondition> conditions) {
        List<String> notes = new ArrayList<>();
        // (id, effectiveRating) tuples for conditions that survive the filter.
        // Using parallel structures because we need the id later for bilateral
        // grouping but only the rating goes into VaMathService.
        record Active(Long id, String name, String code, int rating) {}
        List<Active> active = new ArrayList<>();

        for (IdentifiedCondition c : conditions) {
            Integer rating = c.getEstimatedRating();
            if (rating == null || rating <= 0) continue;

            String reason = c.getPyramidReason();
            if (reason != null && !reason.isBlank()) {
                notes.add(String.format(
                        "'%s' absorbed by pyramiding rule (%s) — excluded from combined rating.",
                        c.getName(), reason));
                continue;
            }

            int effective = rating;
            if (TINNITUS_CODE.equals(c.getVasrdCode()) && effective > TINNITUS_MAX) {
                notes.add(String.format(
                        "'%s' (tinnitus) capped at %d%% per VASRD §4.87 / 6260.",
                        c.getName(), TINNITUS_MAX));
                effective = TINNITUS_MAX;
            }

            active.add(new Active(c.getId(), c.getName(), c.getVasrdCode(), effective));
        }

        // Bilateral pair detection: same non-blank VASRD code with exactly
        // two occurrences. Three or more sharing a code is unusual and
        // ambiguous — leave as individual ratings, no bilateral factor.
        Map<String, List<Active>> byCode = active.stream()
                .filter(a -> a.code() != null && !a.code().isBlank())
                .collect(Collectors.groupingBy(Active::code));

        Set<Long> pairedIds = new HashSet<>();
        List<int[]> bilateralPairs = new ArrayList<>();
        for (var entry : byCode.entrySet()) {
            if (entry.getValue().size() == 2) {
                Active a = entry.getValue().get(0);
                Active b = entry.getValue().get(1);
                bilateralPairs.add(new int[]{a.rating(), b.rating()});
                if (a.id() != null) pairedIds.add(a.id());
                if (b.id() != null) pairedIds.add(b.id());
                notes.add(String.format(
                        "Bilateral pair detected: '%s' + '%s' (VASRD %s) — §4.26 +10%% factor applies.",
                        a.name(), b.name(), entry.getKey()));
            }
        }

        // Ratings list excludes paired items — the math service combines
        // them via the bilateralPairs argument and applies the +10% factor.
        // Conditions without an id (test fixtures) are treated as unpaired.
        List<Integer> ratings = active.stream()
                .filter(a -> a.id() == null || !pairedIds.contains(a.id()))
                .map(Active::rating)
                .collect(Collectors.toList());

        return new Plan(ratings, bilateralPairs, notes);
    }
}
