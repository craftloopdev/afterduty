package com.afterduty.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.IdentifiedCondition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical-JSON → SHA-256 digest of a phase's active generation (spec §2.2 /
 * §2.5). The snapshot unit: a behavior change to the pipeline shows up as a digest
 * diff that must be regenerated deliberately, never silently absorbed.
 *
 * <p>The digest deliberately includes ONLY veteran-visible end-state fields
 * (spec risk §10.8 — resist adding internals): outcome, plus per active condition
 * (sorted by identityFingerprint) name, DC, body system, rating, presumptive flag,
 * and the SHORTENED set of gap "kinds" hit. Internal ids, timestamps, fingerprints,
 * confidences, and rationale prose are excluded so benign churn doesn't move the
 * digest.
 */
public final class EndStateDigest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Compute {@code "sha256:<hex>"} over the canonical digest body. */
    public String digest(PipelineEndState end) {
        String canonical = canonicalJson(end);
        return "sha256:" + sha256Hex(canonical);
    }

    /** The canonical JSON body the digest hashes — exposed for snapshot debugging. */
    public String canonicalJson(PipelineEndState end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", end.outcome());

        List<IdentifiedCondition> sorted = new ArrayList<>(end.activeConditions());
        sorted.sort(Comparator.comparing(
                c -> c.getIdentityFingerprint() == null ? "" : c.getIdentityFingerprint()));

        List<Map<String, Object>> conditions = new ArrayList<>();
        for (IdentifiedCondition c : sorted) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("name", c.getName());
            cm.put("dc", c.getVasrdCode());
            cm.put("body_system", c.getBodySystem());
            cm.put("rating", c.getEstimatedRating());
            cm.put("presumptive", Boolean.TRUE.equals(c.getIsPresumptive()));
            cm.put("pyramid_reason", blankToNull(c.getPyramidReason()));
            cm.put("gap_count", c.getGaps() == null ? 0 : c.getGaps().size());
            cm.put("whatif_count", c.getWhatIfScenarios() == null ? 0 : c.getWhatIfScenarios().size());
            conditions.add(cm);
        }
        body.put("conditions", conditions);

        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to canonicalize end state", e);
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
