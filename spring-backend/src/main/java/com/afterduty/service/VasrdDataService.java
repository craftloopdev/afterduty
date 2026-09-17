package com.afterduty.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.VasrdRecord;
import com.afterduty.repository.VasrdRecordRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * VASRD diagnostic code lookup service.
 *
 * <p>Increment 7 (§C.2): now <b>DB-first</b>. {@link #getByCode}/{@link #search} consult
 * the ingested {@code vasrd_records} table (Part-4 eCFR, point-in-time) and fall back to
 * the bundled {@code vasrd_codes.json} (~35 curated codes) when the table is empty —
 * cold boot, KB disabled, or tests. Signature-compatible: existing callers
 * ({@code RatingAgent}, {@code ConditionController}) read {@code code}/{@code name}/
 * {@code body_system}/{@code rating_levels}/{@code rating_criteria} and those keys are
 * present in both the DB-built and the JSON maps.
 */
@Service
public class VasrdDataService {

    private final VasrdRecordRepository vasrdRecordRepository;

    private List<Map<String, Object>> vasrdCodes = new ArrayList<>();

    public VasrdDataService(VasrdRecordRepository vasrdRecordRepository) {
        this.vasrdRecordRepository = vasrdRecordRepository;
    }

    @PostConstruct
    public void init() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            vasrdCodes = mapper.readValue(
                    new ClassPathResource("vasrd_codes.json").getInputStream(),
                    new TypeReference<>() {}
            );
        } catch (IOException e) {
            // Fallback: initialize with empty list -- codes will be loaded from DB or API later
            vasrdCodes = new ArrayList<>();
        }
    }

    /**
     * Search VASRD codes by name, code number, or body system. DB-first; JSON fallback
     * when {@code vasrd_records} is empty.
     */
    public List<Map<String, Object>> search(String query) {
        if (query == null || query.trim().length() < 2) return List.of();
        String q = query.toLowerCase().trim();

        List<Map<String, Object>> source = dbCodesOrNull();
        if (source == null) source = vasrdCodes;  // JSON fallback

        return source.stream()
                .filter(code -> {
                    String codeNum = ((String) code.getOrDefault("code", "")).toLowerCase();
                    String name = ((String) code.getOrDefault("name", "")).toLowerCase();
                    String bodySystem = ((String) code.getOrDefault("body_system", "")).toLowerCase();
                    return codeNum.contains(q) || name.contains(q) || bodySystem.contains(q);
                })
                .collect(Collectors.toList());
    }

    /**
     * Get a specific VASRD code by its number. DB-first; JSON fallback when the table
     * has no rows for that code.
     */
    public Optional<Map<String, Object>> getByCode(String code) {
        if (code == null) return Optional.empty();
        List<VasrdRecord> records = vasrdRecordRepository.findByDcCodeOrderByDisplayOrder(code);
        if (!records.isEmpty()) {
            return Optional.of(toCodeMap(code, records));
        }
        // JSON fallback.
        return vasrdCodes.stream()
                .filter(c -> code.equals(c.get("code")))
                .findFirst();
    }

    /** All loaded JSON codes (the curated set; unchanged). */
    public List<Map<String, Object>> getAll() {
        return Collections.unmodifiableList(vasrdCodes);
    }

    // -------------------------------------------------------------------------
    // DB → map adaptation (keeps the JSON-shaped contract callers depend on)
    // -------------------------------------------------------------------------

    /**
     * Build the per-code map from DB rows, grouped by dc_code so {@link #search} can
     * filter the same way it filters JSON. Returns null when the table is empty (caller
     * falls back to JSON) to avoid scanning the whole table on every search.
     */
    private List<Map<String, Object>> dbCodesOrNull() {
        List<VasrdRecord> all = vasrdRecordRepository.findAll();
        if (all.isEmpty()) return null;
        Map<String, List<VasrdRecord>> byCode = new LinkedHashMap<>();
        for (VasrdRecord r : all) {
            byCode.computeIfAbsent(r.getDcCode(), k -> new ArrayList<>()).add(r);
        }
        List<Map<String, Object>> out = new ArrayList<>(byCode.size());
        for (var entry : byCode.entrySet()) {
            entry.getValue().sort(Comparator.comparing(
                    r -> r.getDisplayOrder() == null ? Integer.MAX_VALUE : r.getDisplayOrder()));
            out.add(toCodeMap(entry.getKey(), entry.getValue()));
        }
        return out;
    }

    private Map<String, Object> toCodeMap(String code, List<VasrdRecord> records) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        String name = records.stream()
                .map(VasrdRecord::getTitle)
                .filter(t -> t != null && !t.isBlank())
                .findFirst().orElse("");
        map.put("name", name);
        map.put("body_system", records.stream()
                .map(VasrdRecord::getBodySystem)
                .filter(b -> b != null && !b.isBlank())
                .findFirst().orElse(""));
        map.put("cfr_section", records.stream()
                .map(VasrdRecord::getCfrSection)
                .filter(s -> s != null && !s.isBlank())
                .findFirst().orElse(""));
        // as_of_date so callers / the vasrd_lookup tool can cite "current as of".
        records.stream()
                .map(VasrdRecord::getAsOfDate)
                .filter(Objects::nonNull)
                .findFirst()
                .ifPresent(d -> map.put("as_of_date", d.toString()));

        // rating_levels: pct → criteria (matches the JSON shape).
        Map<String, String> levels = new LinkedHashMap<>();
        StringBuilder rendered = new StringBuilder();
        for (VasrdRecord r : records) {
            String key = r.getRatingPct() == null ? "note" : String.valueOf(r.getRatingPct());
            String criteria = r.getCriteriaText() == null ? "" : r.getCriteriaText();
            levels.put(key, criteria);
            if (r.getRatingPct() != null) {
                rendered.append(r.getRatingPct()).append("%: ");
            }
            rendered.append(criteria).append("\n");
        }
        map.put("rating_levels", levels);
        map.put("rating_criteria", rendered.toString().strip());
        return map;
    }
}
