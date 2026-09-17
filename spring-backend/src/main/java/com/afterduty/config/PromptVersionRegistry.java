package com.afterduty.config;

import com.afterduty.service.extraction.SinglePassExtractionService;
import com.afterduty.service.synthesis.ConditionGenerationService;
import com.afterduty.service.synthesis.ConditionIdentificationAgent;

/**
 * Single authoritative home for the prompt/schema version constants that the
 * Increment 8 eval-harness snapshot gate watches.
 *
 * <p>The constants themselves still live next to the services that bake them into
 * their cache keys (so the coupling stays obvious at the point of use); this class
 * re-exports them at a stable, package-public address so the eval harness'
 * {@code PromptVersionEvalGateTest} can read them without reaching into service
 * internals.
 *
 * <p><b>The gate contract (spec §2.5):</b> bumping any of these constants changes
 * the value here, which makes {@code PromptVersionEvalGateTest} fail against the
 * checked-in {@code golden/snapshots/offline-summary.json} until the snapshot is
 * deliberately regenerated. Regeneration ({@code ./gradlew evalSnapshot
 * -PevalRunId=<id>}) is BUILD-enforced to link to a real scored run: the named run
 * must have a committed {@code docs/qa/evals/runs/<id>/report.json} whose
 * {@code prompt_versions} match the constants being stamped, OR the operator must
 * pass an explicit {@code -PevalWaiver="<reason>"} (the conscious-waiver path, now a
 * logged invocation flag rather than honor-system). A prompt/schema/rating-prompt
 * change therefore cannot ship without either a committed scored live run on these
 * same prompts, or an explicit, reviewable waiver.
 *
 * <p>Inc7's chat system-prompt version joins this registry when chat evals arrive.
 */
public final class PromptVersionRegistry {

    private PromptVersionRegistry() {
    }

    /** Single-pass extraction prompt version — see {@link SinglePassExtractionService#PROMPT_VERSION}. */
    public static final String EXTRACTION_PROMPT_VERSION = SinglePassExtractionService.PROMPT_VERSION;

    /** Single-pass extraction output-schema version — see {@link SinglePassExtractionService#SCHEMA_VERSION}. */
    public static final String EXTRACTION_SCHEMA_VERSION = SinglePassExtractionService.SCHEMA_VERSION;

    /** Rating prompt version — see {@link ConditionGenerationService#RATING_PROMPT_VERSION}. */
    public static final String RATING_PROMPT_VERSION = ConditionGenerationService.RATING_PROMPT_VERSION;

    /**
     * Condition-identify prompt version — see
     * {@link ConditionIdentificationAgent#IDENTIFY_PROMPT_VERSION}. Joined the
     * registry at "2" (Phase B item B2: per-condition {@code supporting_atom_ids}
     * attribution + the presumptive-nexus rule, P1-2's identify half — one
     * coordinated bump).
     */
    public static final String IDENTIFY_PROMPT_VERSION = ConditionIdentificationAgent.IDENTIFY_PROMPT_VERSION;
}
