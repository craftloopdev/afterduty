package com.afterduty.service.kb;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixture-backed {@link EcfrClient} test seam (spec §H.1). Serves the checked-in
 * {@code src/test/resources/kb/} fixtures — a Part-4 sample XML (one GPOTABLE rating
 * table, one table-less section, one malformed table), a Part-3 sample XML, a
 * {@code titles.json}, and a {@code versions} diff JSON — and is scriptable so a test
 * can drive the cold-start, watermark-short-circuit, and changed-sections-only paths
 * without any network. Follows the {@code FakeGcsStorage} substrate convention.
 */
public class FakeEcfrClient implements EcfrClient {

    private TitleFreshness freshness =
            new TitleFreshness(LocalDate.parse("2026-06-09"), LocalDate.parse("2026-06-08"));

    /** Per-part scripted version diffs (default empty). */
    private final Map<Integer, List<SectionVersion>> versionsByPart = new HashMap<>();

    /** Per-part XML overrides; default to the checked-in fixtures. */
    private final Map<Integer, String> partXml = new HashMap<>();

    /** Records every fetch for assertion. */
    public final List<String> fetchedXmlForParts = new ArrayList<>();
    public int titleFreshnessCalls = 0;

    public FakeEcfrClient() {
        partXml.put(4, fixture("part4-sample.xml"));
        partXml.put(3, fixture("part3-sample.xml"));
    }

    // -- scripting -----------------------------------------------------------

    public FakeEcfrClient withFreshness(LocalDate upToDateAsOf, LocalDate latestAmendedOn) {
        this.freshness = new TitleFreshness(upToDateAsOf, latestAmendedOn);
        return this;
    }

    public FakeEcfrClient withVersions(int part, List<SectionVersion> versions) {
        this.versionsByPart.put(part, versions);
        return this;
    }

    public FakeEcfrClient withPartXml(int part, String xml) {
        this.partXml.put(part, xml);
        return this;
    }

    // -- EcfrClient ----------------------------------------------------------

    @Override
    public TitleFreshness fetchTitleFreshness() {
        titleFreshnessCalls++;
        return freshness;
    }

    @Override
    public List<SectionVersion> fetchVersions(int part, LocalDate since) {
        return versionsByPart.getOrDefault(part, List.of());
    }

    @Override
    public String fetchPartXml(int part, LocalDate date) {
        fetchedXmlForParts.add("part-" + part);
        String xml = partXml.get(part);
        if (xml == null) {
            throw new IllegalStateException("no fixture XML for part " + part);
        }
        return xml;
    }

    // -- helpers -------------------------------------------------------------

    public static String fixture(String name) {
        try (InputStream in = FakeEcfrClient.class.getResourceAsStream("/kb/" + name)) {
            if (in == null) throw new IllegalStateException("missing fixture /kb/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read fixture /kb/" + name, e);
        }
    }
}
