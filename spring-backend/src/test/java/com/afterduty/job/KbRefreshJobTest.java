package com.afterduty.job;

import com.afterduty.repository.KbSectionRepository;
import com.afterduty.service.kb.EcfrClient;
import com.afterduty.service.kb.FakeEcfrClient;
import com.afterduty.service.kb.VasrdIngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.w3c.dom.Document;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Nightly eCFR refresh coverage (spec §C.5, §H.1): cold-start full ingest (Part 4 every
 * DIV8 + Part 3 allowlist), watermark short-circuit when title-38 is unchanged,
 * changed-sections-only re-ingest off a scripted versions diff, and per-section failure
 * isolation. {@link FakeEcfrClient} serves the fixtures; {@link VasrdIngestService} is
 * mocked (its own ingest is covered by {@code VasrdIngestServiceTest}).
 */
@Tag("regression")
class KbRefreshJobTest {

    private FakeEcfrClient ecfr;
    private VasrdIngestService ingest;
    private KbSectionRepository kbSectionRepo;
    private KbRefreshJob job;

    @BeforeEach
    void setup() throws Exception {
        ecfr = new FakeEcfrClient();
        ingest = mock(VasrdIngestService.class);
        kbSectionRepo = mock(KbSectionRepository.class);
        job = new KbRefreshJob(ecfr, ingest, kbSectionRepo);
        setField(job, "kbEnabled", true);

        // A benign default ingest result (records parsed, some chunks).
        when(ingest.ingestSection(anyInt(), anyString(), anyString(), any(Document.class), any(LocalDate.class)))
                .thenReturn(new VasrdIngestService.SectionResult("x", true, 1, 1));
    }

    @Test
    void coldStart_fullBootstrap_ingestsPart4Div8sAndPart3Allowlist() {
        when(kbSectionRepo.count()).thenReturn(0L);  // empty → cold start

        job.refresh();

        ArgumentCaptor<String> sections = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> parts = ArgumentCaptor.forClass(Integer.class);
        verify(ingest, org.mockito.Mockito.atLeastOnce())
                .ingestSection(parts.capture(), anyString(), sections.capture(),
                        any(Document.class), any(LocalDate.class));

        // Part 4: every DIV8 in the sample fixture (4.71a, 4.10, 4.150).
        assertThat(sections.getAllValues()).contains("4.71a", "4.10", "4.150");
        // Part 3: from the presumptives allowlist (3.307, 3.309 are in the fixture).
        assertThat(sections.getAllValues()).contains("3.307", "3.309");
        // Both parts touched.
        assertThat(parts.getAllValues()).contains(4, 3);
    }

    // Adversarial-review minor: the 1.06 MB Part-4 XML must be fetched (and DOM-parsed)
    // AT MOST ONCE per cold-start refresh — previously it was fetched twice (DIV8
    // enumeration + ingest). Assert exactly one fetch per part.
    @Test
    void coldStart_fetchesEachPartXmlExactlyOnce() {
        when(kbSectionRepo.count()).thenReturn(0L);  // cold start

        job.refresh();

        long part4Fetches = ecfr.fetchedXmlForParts.stream().filter("part-4"::equals).count();
        long part3Fetches = ecfr.fetchedXmlForParts.stream().filter("part-3"::equals).count();
        assertThat(part4Fetches).isEqualTo(1L);
        assertThat(part3Fetches).isEqualTo(1L);
    }

    @Test
    void watermarkUnchanged_shortCircuits_noIngest() {
        when(kbSectionRepo.count()).thenReturn(5L);  // non-empty → not cold start
        // Title 38 up_to_date_as_of (2026-06-09) ≤ stored watermark → exit.
        when(kbSectionRepo.findMaxLastIssueDate()).thenReturn(LocalDate.parse("2026-06-09"));

        job.refresh();

        verify(ingest, never()).ingestSection(anyInt(), anyString(), anyString(), any(), any());
    }

    @Test
    void titleAdvanced_reingestsOnlyChangedSections() {
        when(kbSectionRepo.count()).thenReturn(5L);
        when(kbSectionRepo.findMaxLastIssueDate()).thenReturn(LocalDate.parse("2026-06-01"));
        // Title moved forward; only § 4.71a changed in Part 4, nothing in Part 3.
        ecfr.withFreshness(LocalDate.parse("2026-06-09"), LocalDate.parse("2026-06-08"))
            .withVersions(4, List.of(new EcfrClient.SectionVersion("4.71a", LocalDate.parse("2026-06-08"))))
            .withVersions(3, List.of());

        job.refresh();

        ArgumentCaptor<String> sections = ArgumentCaptor.forClass(String.class);
        verify(ingest, org.mockito.Mockito.atLeastOnce())
                .ingestSection(eq(4), anyString(), sections.capture(), any(), any());
        assertThat(sections.getAllValues()).containsExactly("4.71a");
        // Part 3 had no changed sections → never ingested.
        verify(ingest, never()).ingestSection(eq(3), anyString(), anyString(), any(), any());
    }

    @Test
    void perSectionFailure_doesNotAbortTheRun() {
        when(kbSectionRepo.count()).thenReturn(5L);
        when(kbSectionRepo.findMaxLastIssueDate()).thenReturn(LocalDate.parse("2026-06-01"));
        ecfr.withVersions(4, List.of(
                new EcfrClient.SectionVersion("4.71a", LocalDate.parse("2026-06-08")),
                new EcfrClient.SectionVersion("4.10", LocalDate.parse("2026-06-08"))));
        // First section blows up; the second must still be attempted.
        when(ingest.ingestSection(eq(4), anyString(), eq("4.71a"), any(), any()))
                .thenThrow(new RuntimeException("bad table"));
        when(ingest.ingestSection(eq(4), anyString(), eq("4.10"), any(), any()))
                .thenReturn(new VasrdIngestService.SectionResult("4.10", false, 0, 2));

        job.refresh();  // must not throw

        verify(ingest).ingestSection(eq(4), anyString(), eq("4.10"), any(), any());
    }

    @Test
    void noFreshnessDate_skipsGracefully() {
        ecfr.withFreshness(null, null);
        job.refresh();
        verify(ingest, never()).ingestSection(anyInt(), anyString(), anyString(), any(), any());
    }

    @Test
    void kbDisabled_scheduledIsNoop() throws Exception {
        setField(job, "kbEnabled", false);
        job.scheduled();
        verify(ingest, never()).ingestSection(anyInt(), anyString(), anyString(), any(), any());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = KbRefreshJob.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
