package com.afterduty.service.synthesis;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rating honesty (owner-approved) — the curated code → required objective evidence map.
 *
 * <p>A VA rating produced by the LLM can carry a high self-reported confidence even when
 * the OBJECTIVE measure the code's rating tiers actually hinge on is entirely absent from
 * the evidence (e.g. asthma 6602 rated 30% with NO pulmonary-function test / FEV-1 on
 * file). This class names, for a small curated set of high-value QUANTITATIVE codes, the
 * one measure that decides the rating and the keywords that detect it in evidence-atom
 * text. {@code RatingEvidenceAssessor} uses it to (a) tag an estimate "needs &lt;measure&gt;"
 * when the measure is genuinely missing and (b) temper the served confidence.
 *
 * <p>DELIBERATELY SMALL + CONSERVATIVE. Only codes whose tiers rest on ONE unambiguous
 * quantitative test belong here. An UNMAPPED code returns {@link Optional#empty()} and is
 * NEVER tagged — we never guess a requirement. To extend: add a {@link Requirement} to
 * {@link #REQUIREMENTS} with (1) the code predicate (exact codes / a range / a body
 * system), (2) the plain-language measure name shown to the veteran, and (3) lowercase
 * atom-detection keywords. Keep keywords specific enough that unrelated evidence can't
 * spuriously satisfy them (they gate a "measure present" claim).
 */
@Service
public class RatingEvidenceRequirements {

    /**
     * One code-family → objective-measure mapping.
     *
     * @param label            human-readable id (respiratory-pft, hearing-audiogram, …) — logging/debug only.
     * @param measureName      plain-language name of the objective measure, e.g.
     *                         "a pulmonary function test (PFT/FEV-1)". Rendered into the
     *                         "needs …" note verbatim, so phrase it to read after "needs ".
     * @param keywords         lowercase substrings that, if any appears in an atom's text,
     *                         indicate the measure IS on file. Curated to be specific.
     * @param codeMatcher      returns true when this requirement governs the given (already
     *                         cleaned) VASRD code. Codes are matched on their leading 4-digit
     *                         diagnostic number so suffixes ("6602-...") still match.
     */
    public record Requirement(String label, String measureName, List<String> keywords,
                              java.util.function.Predicate<Integer> codeMatcher) {
        boolean matchesCode(int numericCode) {
            return codeMatcher.test(numericCode);
        }
    }

    // -------------------------------------------------------------------------
    // THE CURATED MAP. Add high-value quantitative codes here (see class doc).
    // -------------------------------------------------------------------------
    private static final List<Requirement> REQUIREMENTS = List.of(
            // Respiratory (asthma 6602, chronic bronchitis 6600, emphysema 6603, COPD 6604,
            // 6825 diffuse interstitial fibrosis, 6840–6846 restrictive lung disease/
            // sarcoidosis): every tier turns on FEV-1 / FVC / FEV-1:FVC from a pulmonary
            // function test. NOTE 6847 (sleep apnea) is deliberately EXCLUDED — it is
            // graded on a sleep study + CPAP requirement, not a PFT.
            new Requirement(
                    "respiratory-pft",
                    "a pulmonary function test (PFT/FEV-1)",
                    List.of("fev-1", "fev1", "fev 1", "fvc", "pft", "pulmonary function", "spirometr"),
                    code -> (code >= 6600 && code <= 6604)
                            || code == 6825
                            || (code >= 6840 && code <= 6846)),

            // Hearing loss 6100: the rating table is a mechanical lookup of a puretone
            // audiogram (decibel thresholds) crossed with a Maryland CNC speech-
            // discrimination score. With neither, the tier cannot be objectively set.
            new Requirement(
                    "hearing-audiogram",
                    "an audiogram + speech discrimination (Maryland CNC)",
                    List.of("audiogram", "puretone", "pure tone", "pure-tone",
                            "speech discrimination", "maryland cnc", "decibel"),
                    code -> code == 6100)
    );

    /**
     * The objective-evidence requirement governing this VASRD code, or empty when the
     * code is unmapped (→ never tagged). Null/blank/non-numeric codes are unmapped.
     * The code is matched on its leading 4-digit diagnostic number, so a suffixed code
     * such as {@code "6847-6602"} still resolves to its family.
     */
    public Optional<Requirement> forCode(String vasrdCode) {
        Integer numeric = leadingNumericCode(vasrdCode);
        if (numeric == null) return Optional.empty();
        for (Requirement r : REQUIREMENTS) {
            if (r.matchesCode(numeric)) return Optional.of(r);
        }
        return Optional.empty();
    }

    /**
     * True when the required objective measure appears in ANY of the supplied atom texts
     * (case-insensitive keyword hit). Pure/deterministic — no side effects. Null-safe:
     * null texts are skipped.
     */
    public boolean measurePresent(Requirement requirement, List<String> atomTexts) {
        if (requirement == null || atomTexts == null) return false;
        for (String text : atomTexts) {
            if (text == null) continue;
            String lower = text.toLowerCase();
            for (String kw : requirement.keywords()) {
                if (lower.contains(kw)) return true;
            }
        }
        return false;
    }

    /**
     * Extract the leading 4-digit VASRD diagnostic number from a code string. Handles a
     * bare "6602", a suffixed "6602-1" / "6847-6602", and surrounding whitespace. Returns
     * null when no 4-digit prefix is present (→ unmapped, never tagged).
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
            // First 4 digits are the diagnostic code; ignore any longer numeric run.
            return Integer.parseInt(digits.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Exposed for tests / callers that want to enumerate the curated map. */
    public List<Requirement> all() {
        return new ArrayList<>(REQUIREMENTS);
    }
}
