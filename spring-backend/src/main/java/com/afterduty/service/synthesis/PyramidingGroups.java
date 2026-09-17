package com.afterduty.service.synthesis;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Deterministic mapping of a VASRD diagnostic code to a canonical "pyramiding
 * group" — a set of conditions the VA rates TOGETHER under one rating formula
 * rather than as separate stacking evaluations.
 *
 * <p>Veterans routinely see, say, PTSD 70% + Major Depressive Disorder 50% +
 * anxiety 30% as three flat rows and assume the ratings add. They don't: under
 * §4.130's General Rating Formula for Mental Disorders, ALL service-connected
 * mental-health conditions are combined into a SINGLE evaluation — the most
 * disabling picture sets the rating, the others do not stack (§4.14 pyramiding:
 * "the evaluation of the same disability under various diagnoses is to be
 * avoided"). {@link PyramidingRules} already encodes the MATH (it excludes the
 * absorbed members from the combined rating); this class is the deterministic,
 * curated SOURCE OF TRUTH for WHICH conditions belong to the same group, so the
 * grouping no longer depends on the unreliable LLM-set {@code pyramidGroup}.
 *
 * <p>DELIBERATELY SMALL + CURATED + EXTENSIBLE. Only code families that VA rates
 * under one shared formula belong here. An UNMAPPED code returns
 * {@link Optional#empty()} and gets NO group — we never guess. To extend: add a
 * {@link Group} to {@link #GROUPS} with (1) the canonical group label shown to
 * the veteran and (2) the code predicate (matched on the leading 4-digit
 * diagnostic number, so suffixed codes like {@code "9411-9434"} still resolve).
 *
 * <h3>TBI (8045) handling — the conservative choice, documented</h3>
 * Traumatic brain injury (DC 8045) is rated on THREE facets — cognitive,
 * emotional/behavioral, and physical. Its emotional/behavioral residuals
 * genuinely overlap the §4.130 mental-disorders formula, and VA rules that when
 * TBI's emotional/behavioral dysfunction CANNOT be distinguished from a
 * co-existing mental-health condition, a single evaluation is assigned under
 * whichever criteria yield the higher rating — BUT TBI's SEPARATE physical and
 * cognitive residuals are rated on their own and must NOT be absorbed.
 *
 * <p>Silently merging 8045 into the mental-health group would therefore risk
 * DOUBLE-DISCOUNTING a veteran's distinct physical/cognitive TBI disability. So
 * the conservative rule adopted here is: <b>8045 is NOT placed in the
 * mental-health group.</b> It is instead flagged with an OVERLAP NOTE
 * ({@link #overlapNote(String)}) explaining that TBI's emotional/behavioral
 * residuals MAY overlap the mental-health rating and are not separately stacked
 * for that facet, while its physical/cognitive residuals are rated on their own.
 * TBI keeps its own row and its own rating; only an advisory note is attached.
 * This never absorbs TBI and never changes its number.
 */
@Component
public class PyramidingGroups {

    /** Canonical label for the §4.130 mental-disorders group (rendered to veterans). */
    public static final String MENTAL_HEALTH_GROUP = "Mental Health (§4.130)";

    /** VASRD diagnostic code for TBI — handled via an overlap note, never grouped. */
    public static final int TBI_CODE = 8045;

    /**
     * One code-family → pyramiding-group mapping.
     *
     * @param label       canonical group label shown to the veteran ("Mental Health (§4.130)").
     * @param codeMatcher true when this group governs the given (leading 4-digit) VASRD code.
     */
    public record Group(String label, java.util.function.Predicate<Integer> codeMatcher) {
        boolean matchesCode(int numericCode) {
            return codeMatcher.test(numericCode);
        }
    }

    // -------------------------------------------------------------------------
    // THE CURATED MAP. Add code families VA rates under ONE formula (see class doc).
    // -------------------------------------------------------------------------
    private static final List<Group> GROUPS = List.of(
            // Mental disorders — §4.130 General Rating Formula. Every mental-disorder
            // diagnostic code is in the 9201–9440 range: schizophrenia/psychotic
            // (9201–9211), delirium/dementia/amnestic & other cognitive (9300–9327),
            // anxiety incl. PTSD (9400 anxiety, 9411 PTSD, 9412 panic, 9413 anxiety
            // NOS, …), dissociative (9416–9417), somatic (9421–9424), mood incl.
            // MDD/bipolar (9432 bipolar, 9433 dysthymia, 9434 MDD, 9435 mood NOS),
            // and eating disorders (9520–9521). All are rated under the SINGLE §4.130
            // formula — they never stack; the most disabling picture sets the rating.
            // (9520–9521 eating disorders technically live just outside the 9201–9440
            // block but are governed by §4.130 too, so the matcher includes them.)
            new Group(
                    MENTAL_HEALTH_GROUP,
                    code -> (code >= 9201 && code <= 9440)
                            || (code >= 9520 && code <= 9521))
            // NOTE: TBI 8045 is intentionally NOT here — see the class doc. It gets an
            // advisory overlap note (overlapNote) instead, so its separate physical /
            // cognitive residuals are never absorbed into the mental-health rating.
    );

    /**
     * The canonical pyramiding group governing this VASRD code, or empty when the
     * code is unmapped (→ no group; today's flat behavior). Null/blank/non-numeric
     * codes are unmapped. The code is matched on its leading 4-digit diagnostic
     * number, so a suffixed code such as {@code "9411-9434"} still resolves.
     */
    public Optional<String> groupFor(String vasrdCode) {
        Integer numeric = leadingNumericCode(vasrdCode);
        if (numeric == null) return Optional.empty();
        for (Group g : GROUPS) {
            if (g.matchesCode(numeric)) return Optional.of(g.label());
        }
        return Optional.empty();
    }

    /**
     * True when the code is TBI (8045), whose emotional/behavioral residuals overlap
     * the §4.130 mental-health group but whose physical/cognitive residuals are rated
     * separately. Callers attach {@link #overlapNote(String)} rather than grouping it.
     */
    public boolean isTbiOverlap(String vasrdCode) {
        Integer numeric = leadingNumericCode(vasrdCode);
        return numeric != null && numeric == TBI_CODE;
    }

    /**
     * The advisory note for a TBI (8045) condition when a mental-health group is also
     * present. Plain-language, non-alarming: it explains the emotional/behavioral
     * overlap WITHOUT asserting absorption, and preserves TBI's separate rating.
     *
     * @param mentalPrimaryName the effective (strongest) mental-health condition's name,
     *                          for a concrete reference; a generic phrasing is used when null/blank.
     */
    public String overlapNote(String mentalPrimaryName) {
        String primary = (mentalPrimaryName == null || mentalPrimaryName.isBlank())
                ? "your mental-health condition"
                : mentalPrimaryName;
        return "Note: your TBI's emotional/behavioral symptoms may overlap with "
                + primary + " under the VA's mental-health rating (§4.130) — the VA won't "
                + "count those emotional symptoms twice. Your TBI's separate physical and "
                + "cognitive effects are still rated on their own.";
    }

    /**
     * Extract the leading 4-digit VASRD diagnostic number from a code string. Handles a
     * bare "9411", a suffixed "9411-1" / "9411-9434", and surrounding whitespace.
     * Returns null when no 4-digit prefix is present (→ unmapped, never grouped).
     * Mirrors {@link RatingEvidenceRequirements}'s parser exactly.
     */
    private static Integer leadingNumericCode(String vasrdCode) {
        if (vasrdCode == null) return null;
        String trimmed = vasrdCode.strip();
        if (trimmed.isEmpty()) return null;
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < trimmed.length() && Character.isDigit(trimmed.charAt(i)); i++) {
            digits.append(trimmed.charAt(i));
        }
        if (digits.length() < 4) return null;
        try {
            return Integer.parseInt(digits.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
