package com.afterduty.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Loads the golden corpus from the classpath ({@code golden/manifest.json} plus
 * each case dir) and validates structural invariants (spec §2.2 / §8.1):
 * <ul>
 *   <li>manifest parses and every entry resolves to a {@code case.json}</li>
 *   <li>ids are unique and match the manifest</li>
 *   <li>tags come from the allowed set</li>
 *   <li>every referenced doc / canned file exists on the classpath</li>
 *   <li>every expectation regex compiles</li>
 *   <li>every {@code *_by_vasrd} key resolves against the identify canned output</li>
 * </ul>
 *
 * <p>A subset can be selected via the {@code eval.cases} system property
 * (comma-separated ids) — used by {@code -Peval.cases=} for fast iteration.
 */
public final class GoldenCaseLoader {

    public static final String GOLDEN_ROOT = "golden";
    public static final String MANIFEST = GOLDEN_ROOT + "/manifest.json";

    /** The closed set of tags a case may declare (spec §1.5 roster). */
    public static final Set<String> ALLOWED_TAGS = Set.of(
            "multi-condition", "bilateral", "presumptive", "abstention-expected",
            "non-medical-docs", "contradictory-evidence", "no-conditions-legitimate",
            "deterministic-rating", "pyramiding", "incremental", "messy"
    );

    private final ObjectMapper mapper = new ObjectMapper();
    private final PathMatchingResourcePatternResolver resolver =
            new PathMatchingResourcePatternResolver(getClass().getClassLoader());

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ManifestEntry(String id, String slug, List<String> tags, String status) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Manifest(List<ManifestEntry> cases) {}

    /** A fully-loaded case: metadata, expectation, and its resource base path. */
    public record LoadedCase(GoldenCase caseFile, GoldenExpectation expectation, String basePath) {
        public String id() { return caseFile.id(); }
        public boolean isActive() { return caseFile.isActive(); }
    }

    /**
     * Load + validate every case named in the manifest, honoring the
     * {@code eval.cases} subset filter (for fast pipeline-gate iteration).
     * Throws on the first structural violation (loud failure — the corpus is the
     * harness' contract).
     */
    public List<LoadedCase> loadAll() {
        return loadAll(parseSubset());
    }

    /**
     * Load + validate the ENTIRE manifest, ignoring the {@code eval.cases} subset.
     * The corpus-validation test must always see every case (the subset filter is a
     * convenience for the pipeline gate, not a way to skip corpus checks).
     */
    public List<LoadedCase> loadEntireManifest() {
        return loadAll(Set.of());
    }

    private List<LoadedCase> loadAll(Set<String> subset) {
        Manifest manifest = readJson(MANIFEST, Manifest.class);
        if (manifest == null || manifest.cases() == null || manifest.cases().isEmpty()) {
            throw new IllegalStateException("golden/manifest.json is empty or unparseable");
        }

        Set<String> seenIds = new LinkedHashSet<>();
        List<LoadedCase> out = new ArrayList<>();

        loadTier(manifest, GOLDEN_ROOT, subset, seenIds, out);

        // Private tier (never committed): a second corpus root on the local
        // filesystem, holding cases whose content cannot enter the repository
        // (the expert-confirmed REAL case is PHI). Configured via the
        // golden.private.root system property or GOLDEN_PRIVATE_ROOT env var;
        // absent configuration means the tier silently does not exist (CI).
        String privateRoot = privateRoot();
        if (privateRoot != null) {
            Manifest priv = readJson("file:" + privateRoot + "/manifest.json", Manifest.class);
            if (priv == null || priv.cases() == null || priv.cases().isEmpty()) {
                throw new IllegalStateException(
                        "golden private root configured (" + privateRoot
                        + ") but its manifest.json is missing or empty — explicit config fails loud");
            }
            loadTier(priv, "file:" + privateRoot, subset, seenIds, out);
        }

        if (out.isEmpty()) {
            throw new IllegalStateException(
                    "no golden cases loaded (subset=" + subset + ") — check eval.cases / manifest");
        }
        return out;
    }

    private void loadTier(Manifest manifest, String root, Set<String> subset,
                          Set<String> seenIds, List<LoadedCase> out) {
        for (ManifestEntry entry : manifest.cases()) {
            if (entry.id() == null || entry.slug() == null) {
                throw new IllegalStateException("manifest entry missing id/slug: " + entry);
            }
            if (!seenIds.add(entry.id())) {
                throw new IllegalStateException("duplicate case id in manifest: " + entry.id());
            }
            if (!subset.isEmpty() && !subset.contains(entry.id())) {
                continue;
            }

            String base = root + "/cases/" + entry.id() + "-" + entry.slug();
            GoldenCase caseFile = readJson(base + "/case.json", GoldenCase.class);
            GoldenExpectation expectation = readJson(base + "/expected.json", GoldenExpectation.class);

            validate(entry, caseFile, expectation, base);
            out.add(new LoadedCase(caseFile, expectation, base));
        }
    }

    /** The private corpus root, or null when the tier is not configured. */
    static String privateRoot() {
        String prop = System.getProperty("golden.private.root", "").trim();
        if (!prop.isEmpty()) {
            return prop;
        }
        String env = System.getenv("GOLDEN_PRIVATE_ROOT");
        return (env == null || env.trim().isEmpty()) ? null : env.trim();
    }

    /** Active cases only — the offline gate parameter source. */
    public List<LoadedCase> loadActive() {
        return loadAll().stream().filter(LoadedCase::isActive).toList();
    }

    // ------------------------------------------------------------------ validation

    private void validate(ManifestEntry entry, GoldenCase c, GoldenExpectation e, String base) {
        if (!entry.id().equals(c.id())) {
            throw new IllegalStateException("manifest id " + entry.id()
                    + " != case.json id " + c.id() + " (" + base + ")");
        }
        if (c.tags() != null) {
            for (String tag : c.tags()) {
                if (!ALLOWED_TAGS.contains(tag)) {
                    throw new IllegalStateException("case " + c.id()
                            + " uses tag '" + tag + "' not in allowed set " + ALLOWED_TAGS);
                }
            }
        }
        if (c.phases() == null || c.phases().isEmpty()) {
            throw new IllegalStateException("case " + c.id() + " has no phases");
        }

        // Every doc + canned-extraction file must exist on the classpath.
        for (GoldenCase.Phase phase : c.phases()) {
            if (phase.docs() == null) continue;
            for (GoldenCase.Doc doc : phase.docs()) {
                requireResource(base + "/" + doc.file(), c.id(), "doc");
                requireResource(base + "/" + doc.cannedExtraction(), c.id(), "canned_extraction");
            }
        }
        // Purpose-level canned files must exist.
        GoldenCase.Canned canned = c.canned();
        if (canned != null) {
            requireOptionalResource(base, canned.synthesisIdentify(), c.id());
            requireOptionalResource(base, canned.synthesisDuplicateMerger(), c.id());
            requireOptionalResource(base, canned.synthesisVerify(), c.id());
            requireByVasrd(base, canned.synthesisRateByVasrd(), c.id());
            requireByVasrd(base, canned.gapEvidenceByVasrd(), c.id());
            requireByVasrd(base, canned.gapValidationByVasrd(), c.id());
            requireByVasrd(base, canned.gapWhatifByVasrd(), c.id());
        }

        // Every expectation regex must compile.
        if (e == null || e.phases() == null) {
            throw new IllegalStateException("case " + c.id() + " expected.json has no phases");
        }
        for (var phaseEntry : e.phases().entrySet()) {
            GoldenExpectation.PhaseExpectation pe = phaseEntry.getValue();
            if (pe.conditions() != null) {
                for (GoldenExpectation.ConditionExpectation ce : pe.conditions()) {
                    compileRegex(ce.namePattern(), c.id(), "condition.name_pattern");
                }
            }
            if (pe.gaps() != null) {
                for (GoldenExpectation.GapExpectation ge : pe.gaps()) {
                    compileRegex(ge.pattern(), c.id(), "gap.pattern");
                }
            }
            // Every expected-phase name must exist in case.json.
            if (!c.hasPhase(phaseEntry.getKey())) {
                throw new IllegalStateException("case " + c.id() + " expected.json references phase '"
                        + phaseEntry.getKey() + "' not declared in case.json");
            }
        }
    }

    private void requireByVasrd(String base, java.util.Map<String, String> byVasrd, String caseId) {
        if (byVasrd == null) return;
        for (var entry : byVasrd.entrySet()) {
            // null value ⇒ deterministic-rating DC (no canned file) — legitimate.
            if (entry.getValue() != null) {
                requireResource(base + "/" + entry.getValue(), caseId, "canned " + entry.getKey());
            }
        }
    }

    private void requireOptionalResource(String base, String rel, String caseId) {
        if (rel != null) {
            requireResource(base + "/" + rel, caseId, "canned");
        }
    }

    private void requireResource(String path, String caseId, String what) {
        Resource r = resource(path);
        if (!r.exists()) {
            throw new IllegalStateException("case " + caseId + " references missing " + what
                    + " resource: " + path);
        }
    }

    /**
     * Resolve a corpus path: committed-tier paths are classpath-relative;
     * private-tier paths arrive already carrying their {@code file:} root.
     */
    private Resource resource(String path) {
        return resolver.getResource(path.startsWith("file:") ? path : "classpath:" + path);
    }

    private void compileRegex(String pattern, String caseId, String where) {
        if (pattern == null) {
            throw new IllegalStateException("case " + caseId + " has null regex at " + where);
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException ex) {
            throw new IllegalStateException("case " + caseId + " has invalid regex at " + where
                    + ": " + pattern, ex);
        }
    }

    // ------------------------------------------------------------------ io

    /** Read + parse a corpus JSON resource (either tier). Returns null if absent. */
    <T> T readJson(String path, Class<T> type) {
        Resource r = resource(path);
        if (!r.exists()) {
            return null;
        }
        try (InputStream in = r.getInputStream()) {
            return mapper.readValue(in, type);
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to read/parse " + path, ex);
        }
    }

    /** Read a corpus text resource (a doc body or canned response) as a UTF-8 string. */
    public String readText(String basePath, String relative) {
        Resource r = resource(basePath + "/" + relative);
        try (InputStream in = r.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to read " + basePath + "/" + relative, ex);
        }
    }

    private Set<String> parseSubset() {
        String prop = System.getProperty("eval.cases", "").trim();
        if (prop.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.asList(prop.split("\\s*,\\s*")));
    }
}
