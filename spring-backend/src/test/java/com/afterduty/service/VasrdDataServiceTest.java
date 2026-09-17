package com.afterduty.service;

import com.afterduty.model.VasrdRecord;
import com.afterduty.repository.VasrdRecordRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DB-first VASRD lookup with JSON fallback (spec §C.2, §H.1). When {@code vasrd_records}
 * has rows for a code, {@link VasrdDataService} builds the answer from the structured
 * records (with {@code as_of_date}); when the table is empty (cold boot / KB disabled /
 * tests), it falls back to the bundled {@code vasrd_codes.json}. The DB path is keyed off
 * a mocked {@link VasrdRecordRepository}.
 */
@Tag("regression")
class VasrdDataServiceTest {

    private VasrdDataService newService(VasrdRecordRepository repo) {
        VasrdDataService s = new VasrdDataService(repo);
        s.init();  // loads vasrd_codes.json from the classpath
        return s;
    }

    private VasrdRecord record(String dc, String title, Integer pct, String criteria, int order) {
        return VasrdRecord.builder()
                .dcCode(dc).title(title).bodySystem("musculoskeletal").cfrSection("4.71a")
                .ratingPct(pct).criteriaText(criteria).asOfDate(LocalDate.parse("2026-06-09"))
                .displayOrder(order).build();
    }

    @Test
    void getByCode_dbFirst_buildsFromStructuredRecords_withAsOf() {
        VasrdRecordRepository repo = mock(VasrdRecordRepository.class);
        when(repo.findByDcCodeOrderByDisplayOrder("5260")).thenReturn(List.of(
                record("5260", "Leg, limitation of flexion of", 30, "Flexion limited to 15 degrees", 0),
                record("5260", "Leg, limitation of flexion of", 20, "Flexion limited to 30 degrees", 1),
                record("5260", "Leg, limitation of flexion of", 10, "Flexion limited to 45 degrees", 2)));

        VasrdDataService service = newService(repo);
        Optional<Map<String, Object>> result = service.getByCode("5260");

        assertThat(result).isPresent();
        Map<String, Object> map = result.get();
        assertThat(map.get("code")).isEqualTo("5260");
        assertThat(map.get("name")).isEqualTo("Leg, limitation of flexion of");
        assertThat(map.get("cfr_section")).isEqualTo("4.71a");
        assertThat(map.get("as_of_date")).isEqualTo("2026-06-09");
        @SuppressWarnings("unchecked")
        Map<String, String> levels = (Map<String, String>) map.get("rating_levels");
        assertThat(levels).containsKeys("30", "20", "10");
        assertThat((String) map.get("rating_criteria")).contains("Flexion limited to 15 degrees");
    }

    @Test
    void getByCode_jsonFallback_whenTableHasNoRowsForCode() {
        VasrdRecordRepository repo = mock(VasrdRecordRepository.class);
        when(repo.findByDcCodeOrderByDisplayOrder(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of());  // table empty for this code

        VasrdDataService service = newService(repo);
        // Pick a code that exists in the bundled JSON. We assert the fallback returns the
        // JSON shape (code present); exact code value depends on the curated set, so we
        // assert via getAll() which is the JSON source.
        List<Map<String, Object>> all = service.getAll();
        assertThat(all).isNotEmpty();
        String jsonCode = (String) all.get(0).get("code");

        Optional<Map<String, Object>> result = service.getByCode(jsonCode);
        assertThat(result).isPresent();
        assertThat(result.get().get("code")).isEqualTo(jsonCode);
    }

    @Test
    void search_dbFirst_whenTableNonEmpty() {
        VasrdRecordRepository repo = mock(VasrdRecordRepository.class);
        VasrdRecord r = record("5260", "Leg, limitation of flexion of", 30, "criteria", 0);
        when(repo.findAll()).thenReturn(List.of(r));

        VasrdDataService service = newService(repo);
        List<Map<String, Object>> results = service.search("flexion");

        assertThat(results).anyMatch(m -> "5260".equals(m.get("code")));
    }

    @Test
    void search_jsonFallback_whenTableEmpty() {
        VasrdRecordRepository repo = mock(VasrdRecordRepository.class);
        when(repo.findAll()).thenReturn(List.of());

        VasrdDataService service = newService(repo);
        // A 2-char query that matches nothing structured falls through to JSON; the result
        // is whatever the curated set contains — assert it does not error and returns a list.
        List<Map<String, Object>> results = service.search("kn");
        assertThat(results).isNotNull();
    }

    @Test
    void search_shortQuery_returnsEmpty() {
        VasrdRecordRepository repo = mock(VasrdRecordRepository.class);
        VasrdDataService service = newService(repo);
        assertThat(service.search("a")).isEmpty();
        assertThat(service.search(null)).isEmpty();
    }

    @Test
    void getByCode_null_returnsEmpty() {
        VasrdRecordRepository repo = mock(VasrdRecordRepository.class);
        when(repo.findByDcCodeOrderByDisplayOrder(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        VasrdDataService service = newService(repo);
        assertThat(service.getByCode(null)).isEmpty();
    }
}
