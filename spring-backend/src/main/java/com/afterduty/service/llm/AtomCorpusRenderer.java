package com.afterduty.service.llm;

import com.afterduty.model.Atom;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Renders the LIVE atom corpus as a single, byte-stable text block to be carried
 * as the {@link LlmJobRequest.StructuredPrompt} {@code cachedCorpus} — the one
 * {@code cache_control} breakpoint shared by every per-condition fan-out call in a
 * synthesis/gap run (Increment 6 / Mission 6b, requirements 1+2).
 *
 * <h2>Why this exists (the O(conditions × atoms) killer)</h2>
 * Today {@code RatingAgent}/{@code EvidenceGapAnalyzer}/{@code GapValidationAgent}
 * each fold the entire atom corpus into the per-condition {@code userMessage}, so a
 * 12-condition claim re-sends the whole corpus 12× uncached. Instead, the corpus
 * becomes a frozen prefix block cached once and read at 0.1× on every subsequent
 * per-condition call; only the single condition rides the volatile tail
 * ({@code userMessage}) after the breakpoint.
 *
 * <h2>Byte-stability contract (cache-key hygiene)</h2>
 * The Anthropic cache is a strict prefix match over rendered bytes (research §5):
 * any byte change after the last common breakpoint invalidates the cache. For every
 * rate/gap call in ONE run to share the identical cached prefix, this block MUST be
 * byte-identical across calls. Therefore:
 * <ul>
 *   <li><b>Deterministic order</b> — atoms are sorted by {@code (evidence_id, id)}
 *       (both nulls-last) so the corpus bytes do not depend on the DB row order the
 *       caller happened to fetch.</li>
 *   <li><b>LIVE only</b> — superseded atoms (retired by a re-extraction) are filtered
 *       out defensively; the callers already pass {@code findByClaimIdAndSupersededByIsNull},
 *       but filtering here keeps the block honest regardless of caller.</li>
 *   <li><b>No volatile content</b> — no "now"/wall-clock timestamps, no UUIDs, and no
 *       counts-that-change (the running atom count is deliberately NOT rendered into
 *       the cached block — the per-condition volatile tail states it instead). Only
 *       per-atom data already on the row (type/value/source/confidence/atom timestamp)
 *       is emitted, each formatted with a fixed {@link Locale#ROOT} pattern so a
 *       locale-dependent decimal separator can't perturb the bytes.</li>
 * </ul>
 *
 * <p>Two renders of the same LIVE atom set (in any input order) are byte-equal — the
 * property the prefix-stability tests assert.
 */
public final class AtomCorpusRenderer {

    private AtomCorpusRenderer() {}

    /** Stable header marking the start of the cached corpus block (no counts/timestamps). */
    static final String CORPUS_HEADER = "EVIDENCE ATOM CORPUS (sorted, stable across this run):";

    /**
     * Renders the LIVE atoms as the byte-stable cached-corpus block. Returns a block
     * that is identical for any permutation of the same LIVE atom set. An empty/blank
     * corpus (no live atoms) renders a fixed sentinel line so the block is still
     * stable (and below the cacheable minimum, so the tripwire correctly stays silent).
     */
    public static String render(List<Atom> atoms) {
        StringBuilder sb = new StringBuilder(CORPUS_HEADER).append('\n');
        List<Atom> live = atoms == null ? List.of() : atoms.stream()
                .filter(a -> a != null && a.getSupersededBy() == null)
                .sorted(ATOM_ORDER)
                .toList();
        if (live.isEmpty()) {
            sb.append("(no evidence atoms on file)");
            return sb.toString();
        }
        for (Atom a : live) {
            sb.append("- [").append(nullToEmpty(a.getType())).append("] ")
                    .append(nullToEmpty(a.getValue()));
            if (a.getTimestamp() != null && !a.getTimestamp().isBlank()) {
                sb.append(" [").append(a.getTimestamp()).append(']');
            }
            sb.append(" (source: ").append(nullToEmpty(a.getSource()))
                    .append(", confidence: ")
                    .append(String.format(Locale.ROOT, "%.2f", a.getConfidence() == null ? 0.0 : a.getConfidence()))
                    .append(")\n");
        }
        // Trim the trailing newline so the block bytes are exactly determined by content.
        if (sb.charAt(sb.length() - 1) == '\n') sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    /**
     * Deterministic ordering: by {@code evidence_id} then {@code id}, both nulls-last,
     * so the bytes never depend on the fetch order. {@code id} is unique per atom, so
     * the total order is a strict, reproducible sort.
     */
    private static final Comparator<Atom> ATOM_ORDER =
            Comparator.comparing(Atom::getEvidenceId, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(Atom::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
