package com.afterduty.eval;

import com.afterduty.model.Claim;
import com.afterduty.model.IdentifiedCondition;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link EndStateDigest} (spec §8.3): digest stability under
 * condition reordering and digest sensitivity to a rating change.
 */
@Tag("regression")
class EndStateDigestTest {

    private final EndStateDigest digest = new EndStateDigest();

    private IdentifiedCondition cond(String name, String dc, int rating, String fp) {
        IdentifiedCondition c = IdentifiedCondition.builder()
                .claimId(1L).name(name).vasrdCode(dc).bodySystem("mental")
                .estimatedRating(rating).build();
        c.setIdentityFingerprint(fp);
        return c;
    }

    private PipelineEndState end(List<IdentifiedCondition> active) {
        return new PipelineEndState("complete", new Claim(), active, active,
                List.of(), List.of(), List.of());
    }

    @Test
    void digest_isStableUnderConditionReordering() {
        var a = cond("PTSD", "9411", 70, "fp-a");
        var b = cond("Tinnitus", "6260", 10, "fp-b");
        String d1 = digest.digest(end(List.of(a, b)));
        String d2 = digest.digest(end(List.of(b, a)));
        assertEquals(d1, d2, "digest must sort by identity fingerprint, so order can't matter");
    }

    @Test
    void digest_changesWhenRatingChanges() {
        var a70 = cond("PTSD", "9411", 70, "fp-a");
        var a50 = cond("PTSD", "9411", 50, "fp-a");
        assertNotEquals(digest.digest(end(List.of(a70))), digest.digest(end(List.of(a50))),
                "a rating change must move the digest");
    }

    @Test
    void digest_changesWhenOutcomeChanges() {
        var a = cond("PTSD", "9411", 70, "fp-a");
        var complete = new PipelineEndState("complete", new Claim(), List.of(a), List.of(a),
                List.of(), List.of(), List.of());
        var failed = new PipelineEndState("failed", new Claim(), List.of(), List.of(),
                List.of(), List.of(), List.of());
        assertNotEquals(digest.digest(complete), digest.digest(failed));
    }

    @Test
    void digest_hasSha256Prefix() {
        assertTrue(digest.digest(end(List.of())).startsWith("sha256:"));
    }
}
