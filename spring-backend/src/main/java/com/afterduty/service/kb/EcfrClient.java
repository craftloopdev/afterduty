package com.afterduty.service.kb;

import java.time.LocalDate;
import java.util.List;

/**
 * Thin eCFR versioner client (Increment 7, spec §C.1). Interface is the test seam —
 * {@code FakeEcfrClient} serves fixture XML/JSON; {@link EcfrClientImpl} hits the real
 * api.gov host. Polite: single-threaded use from the nightly job only.
 */
public interface EcfrClient {

    /** Title 38's overall freshness from {@code /api/versioner/v1/titles.json}. */
    record TitleFreshness(LocalDate upToDateAsOf, LocalDate latestAmendedOn) {}

    /** One section's version from {@code /api/versioner/v1/versions/title-38.json}. */
    record SectionVersion(String sectionIdentifier, LocalDate issueDate) {}

    /** GET /api/versioner/v1/titles.json → title 38's freshness. */
    TitleFreshness fetchTitleFreshness();

    /**
     * GET /api/versioner/v1/versions/title-38.json?part={p}&issue_date[gte]={since}
     * → sections of the part amended on or after {@code since}.
     */
    List<SectionVersion> fetchVersions(int part, LocalDate since);

    /**
     * GET /api/versioner/v1/full/{date}/title-38.xml?part={p} → the part XML at a
     * point in time (1.06 MB for Part 4 — streamed to string).
     */
    String fetchPartXml(int part, LocalDate date);
}
