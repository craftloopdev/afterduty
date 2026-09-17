package com.afterduty.service;

import com.afterduty.model.VasrdRecord;
import com.afterduty.repository.VasrdRecordRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The VASRD index that replaced the scraped masterlist. Same two-tier contract as
 * {@link VasrdDataService}: the ingested {@code vasrd_records} table wins when it has
 * rows, and the bundled {@code vasrd_index.json} (generated from 38 CFR Part 4) answers
 * when it is empty — cold boot, {@code KB_ENABLED=false}, or tests.
 */
@Tag("regression")
class VasrdIndexServiceTest {

    private static final String EAR = "Schedule of ratings—ear.";
    private static final String MSK = "Schedule of ratings—musculoskeletal system.";

    private VasrdIndexService newService(VasrdRecordRepository repo) {
        VasrdIndexService s = new VasrdIndexService(repo);
        s.load();
        return s;
    }

    /** An empty table — the state a cold boot or a test datasource is in. */
    private VasrdRecordRepository emptyRepo() {
        VasrdRecordRepository repo = mock(VasrdRecordRepository.class);
        when(repo.findByDcCodeOrderByDisplayOrder(anyString())).thenReturn(List.of());
        when(repo.findDistinctCodesInBodySystem(anyString())).thenReturn(List.of());
        return repo;
    }

    private VasrdRecordRepository.CodeTitle codeTitle(String dc, String title) {
        VasrdRecordRepository.CodeTitle ct = mock(VasrdRecordRepository.CodeTitle.class);
        when(ct.getDcCode()).thenReturn(dc);
        when(ct.getTitle()).thenReturn(title);
        return ct;
    }

    // ── bundled index ────────────────────────────────────────────────────────

    @Test
    void loadsTheBundledScheduleIncludingTableAssignedCodes() {
        VasrdIndexService s = newService(emptyRepo());

        assertThat(s.all()).hasSizeGreaterThan(700);
        // Ordinary titled rows.
        assertThat(s.findByCode("6260").title()).contains("Tinnitus");
        assertThat(s.findByCode("5237").title()).contains("Lumbosacral");
        // §4.130 lists mental-health codes as flush paragraphs, not table rows.
        assertThat(s.findByCode("9411").title()).contains("stress disorder");
        // Assigned by a table lookup; carried by the generator's supplement.
        assertThat(s.findByCode("6100").title()).contains("Hearing");
        // §4.73's muscle groups carry a paragraph-length description.
        assertThat(s.findByCode("5302").title()).startsWith("Group II");
    }

    @Test
    void coversEveryCodeTheCuratedRatingFileKnows() {
        // vasrd_index.json must not regress the criteria file's coverage — the two
        // are meant to answer different questions about the same schedule.
        VasrdIndexService index = newService(emptyRepo());
        VasrdDataService curated = new VasrdDataService(emptyRepo());
        curated.init();

        for (var entry : curated.getAll()) {
            String code = String.valueOf(entry.get("code"));
            assertThat(index.findByCode(code))
                    .as("code %s present in the index", code)
                    .isNotNull();
        }
    }

    // ── prompt block ─────────────────────────────────────────────────────────

    @Test
    void rendersScheduleEntryWithAnEcfrCitation() {
        String block = newService(emptyRepo())
                .contextForCondition("Tinnitus", "6260", null);

        assertThat(block).contains("Schedule entry: Tinnitus, recurrent (VASRD 6260)");
        assertThat(block).contains("Authority: 38 CFR 4.87");
        assertThat(block).contains(
                "https://www.ecfr.gov/current/title-38/part-4/section-4.87");
        // The body system reads as a name, not the CFR's boilerplate phrasing.
        assertThat(block).contains("— ear");
        assertThat(block).doesNotContain("Schedule of ratings—ear");
    }

    @Test
    void neverCitesTheThirdPartyKnowledgeBaseItReplaced() {
        String block = newService(emptyRepo())
                .contextForCondition("Tinnitus", "6260", null);
        assertThat(block.toLowerCase()).doesNotContain("veteransbenefitskb");
    }

    @Test
    void listsSiblingCodesExcludingTheAnchorAndCapsThem() {
        String block = newService(emptyRepo())
                .contextForCondition("Lumbosacral strain", "5237", null);

        assertThat(block).contains("Nearby codes in musculoskeletal system:");
        long siblings = block.lines().filter(l -> l.startsWith("  ")).count();
        assertThat(siblings).isPositive().isLessThanOrEqualTo(12);
        // The anchor must not appear among its own neighbours.
        assertThat(block.lines().filter(l -> l.startsWith("  5237 ")).count()).isZero();
    }

    @Test
    void abstainsWhenNeitherCodeNorTitleResolves() {
        String block = newService(emptyRepo())
                .contextForCondition("Sasquatch bite", "9999", null);
        assertThat(block).contains("No schedule entry matches 'Sasquatch bite'");
    }

    @Test
    void fallsBackToTitleLookupWhenTheCodeIsUnknown() {
        String block = newService(emptyRepo())
                .contextForCondition("Tinnitus, recurrent", "9999", null);
        assertThat(block).contains("VASRD 6260");
    }

    // ── DB tier wins when populated ──────────────────────────────────────────

    @Test
    void prefersTheIngestedRecordOverTheBundledIndex() {
        VasrdRecordRepository repo = emptyRepo();
        when(repo.findByDcCodeOrderByDisplayOrder("6260")).thenReturn(List.of(
                VasrdRecord.builder()
                        .dcCode("6260").title("Tinnitus, recurrent (amended)")
                        .bodySystem(EAR).cfrSection("4.87")
                        .asOfDate(LocalDate.parse("2026-08-15"))
                        .ratingPct(10).displayOrder(0).build()));

        String block = newService(repo).contextForCondition("Tinnitus", "6260", null);

        assertThat(block).contains("Tinnitus, recurrent (amended)");
        // The citation carries the ingest's point-in-time date, not the bundle's.
        assertThat(block).contains("as of 2026-08-15");
    }

    @Test
    void prefersIngestedSiblingsOverBundledOnes() {
        VasrdRecordRepository repo = emptyRepo();
        // Build the projection stub first — Mockito rejects a mock() created
        // inside an in-progress when(...).
        var sibling = codeTitle("5999", "Newly scheduled condition");
        when(repo.findDistinctCodesInBodySystem(MSK)).thenReturn(List.of(sibling));

        String block = newService(repo).contextForCondition("Lumbosacral strain", "5237", null);

        assertThat(block).contains("5999  Newly scheduled condition");
    }

    @Test
    void survivesARepositoryFailureByFallingBackToTheBundle() {
        VasrdRecordRepository repo = mock(VasrdRecordRepository.class);
        when(repo.findByDcCodeOrderByDisplayOrder(anyString()))
                .thenThrow(new RuntimeException("no such table: vasrd_records"));
        when(repo.findDistinctCodesInBodySystem(anyString()))
                .thenThrow(new RuntimeException("no such table: vasrd_records"));

        String block = newService(repo).contextForCondition("Tinnitus", "6260", null);

        assertThat(block).contains("VASRD 6260");
        assertThat(block).contains("Nearby codes in ear:");
    }

    // ── digest ───────────────────────────────────────────────────────────────

    @Test
    void digestGroupsEveryCodeUnderAReadableBodySystem() {
        String digest = newService(emptyRepo()).toPromptDigest();

        assertThat(digest).startsWith("Canonical VASRD codes by body system");
        assertThat(digest).contains("\near\n").contains("\nMental disorders\n");
        assertThat(digest).contains("  6260  Tinnitus, recurrent");
        assertThat(digest).doesNotContain("Schedule of ratings—");
    }

    @Test
    void displaySystemTrimsTheCfrBoilerplate() {
        assertThat(VasrdIndexService.displaySystem(MSK)).isEqualTo("musculoskeletal system");
        assertThat(VasrdIndexService.displaySystem("Evaluation of hearing impairment."))
                .isEqualTo("Evaluation of hearing impairment");
        assertThat(VasrdIndexService.displaySystem(null)).isEqualTo("this body system");
    }
}
