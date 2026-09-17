package com.afterduty.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.config.PromptVersionRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Increment 8 prompt-bump gate (spec §2.5). Asserts the LIVE prompt/schema/rating
 * version constants ({@link PromptVersionRegistry}) equal the values recorded in
 * the checked-in {@code golden/snapshots/offline-summary.json}.
 *
 * <p>Bumping {@code SinglePassExtractionService.PROMPT_VERSION}/{@code SCHEMA_VERSION}
 * or {@code ConditionGenerationService.RATING_PROMPT_VERSION} therefore fails
 * {@code ./gradlew check} until the snapshot is regenerated — and regeneration
 * ({@code ./gradlew evalSnapshot -PevalRunId=<id>}) requires naming a live-eval run
 * id, so a prompt change cannot ship without someone having run (or consciously
 * waived, with the stale run id left in the diff) the scored live tier.
 *
 * <p>Runs in the default suite (tags {@code eval-offline} + {@code regression}).
 */
@Tag("eval-offline")
@Tag("regression")
class PromptVersionEvalGateTest {

    private static final String SNAPSHOT =
            "golden/snapshots/offline-summary.json";

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String REGEN_HINT =
            "\nRegenerate the snapshot with `./gradlew evalSnapshot -PevalRunId=<live-eval-run-id>` "
                    + "after running (or consciously waiving) the scored live tier.";

    @Test
    @SuppressWarnings("unchecked")
    void liveConstantsMatchSnapshotPromptVersions() throws Exception {
        Map<String, Object> snapshot;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(SNAPSHOT)) {
            assertNotNull(in, "offline snapshot resource not found: " + SNAPSHOT);
            snapshot = mapper.readValue(in, Map.class);
        }

        Map<String, Object> versions = (Map<String, Object>) snapshot.get("prompt_versions");
        assertNotNull(versions, "offline snapshot is missing the prompt_versions block");

        assertVersionsMatch(versions);
    }

    /**
     * Negative path (spec §8.4): a doctored snapshot (a version bumped without
     * regenerating) must fail with an assertion message that NAMES the regeneration
     * command — so a future engineer who bumps {@code PROMPT_VERSION} sees exactly
     * how to acknowledge the live-eval run.
     */
    @Test
    void doctoredSnapshotFails_andMessageNamesRegenerationCommand() {
        Map<String, Object> doctored = new HashMap<>();
        doctored.put("extraction_prompt_version", "DOCTORED-99");   // pretend a bump w/o regen
        doctored.put("extraction_schema_version", PromptVersionRegistry.EXTRACTION_SCHEMA_VERSION);
        doctored.put("rating_prompt_version", PromptVersionRegistry.RATING_PROMPT_VERSION);

        AssertionFailedError failure =
                assertThrows(AssertionFailedError.class, () -> assertVersionsMatch(doctored));
        assertTrue(failure.getMessage().contains("evalSnapshot -PevalRunId="),
                "the gate's failure message must name the regeneration command, was: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("extraction_prompt_version"),
                "the failure must name which version drifted");
    }

    private void assertVersionsMatch(Map<String, Object> versions) {
        assertEquals(PromptVersionRegistry.EXTRACTION_PROMPT_VERSION,
                versions.get("extraction_prompt_version"),
                "extraction_prompt_version drifted from the snapshot." + REGEN_HINT);
        assertEquals(PromptVersionRegistry.EXTRACTION_SCHEMA_VERSION,
                versions.get("extraction_schema_version"),
                "extraction_schema_version drifted from the snapshot." + REGEN_HINT);
        assertEquals(PromptVersionRegistry.RATING_PROMPT_VERSION,
                versions.get("rating_prompt_version"),
                "rating_prompt_version drifted from the snapshot." + REGEN_HINT);
        assertEquals(PromptVersionRegistry.IDENTIFY_PROMPT_VERSION,
                versions.get("identify_prompt_version"),
                "identify_prompt_version drifted from the snapshot." + REGEN_HINT);
    }
}
