package com.afterduty.service.rag;

import com.afterduty.config.PgVectorBootstrap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hybrid retrieval SQL building + binding + branch selection (spec §D, §H.1). The RRF
 * query is Postgres-only (FULL OUTER JOIN over CTEs, {@code <=>}, {@code websearch_to_tsquery})
 * and cannot run on H2 — so this test mocks {@link JdbcTemplate}, captures the SQL and the
 * bound argument array, and asserts:
 *
 * <ul>
 *   <li>{@code searchEvidence} binds {@code claim_id} in BOTH arms (and asserts non-null);</li>
 *   <li>{@code searchKb} binds {@code claim_id IS NULL} in both arms;</li>
 *   <li>the RRF k and per-arm/final limits are bound;</li>
 *   <li>branch selection: hybrid (vector available) vs tsvector-only (no vector / embed fail)
 *       vs naive ILIKE (no full-text).</li>
 * </ul>
 *
 * <p>Real RRF semantics are verified by the §H.4 deployed smoke (or a dockerized
 * pgvector instance), not here.
 */
@Tag("regression")
class HybridRetrievalServiceTest {

    private final FakeEmbeddingProvider embedder = new FakeEmbeddingProvider();

    @SuppressWarnings("unchecked")
    private JdbcTemplate jdbcReturningEmpty() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        return jdbc;
    }

    private HybridRetrievalService service(JdbcTemplate jdbc, PgVectorBootstrap boot) throws Exception {
        HybridRetrievalService s = new HybridRetrievalService(jdbc, embedder, boot);
        setField(s, "topK", 12);
        setField(s, "candidateK", 40);
        setField(s, "rrfK", 60);
        return s;
    }

    private static PgVectorBootstrap boot(boolean vector, boolean fullText) {
        PgVectorBootstrap b = mock(PgVectorBootstrap.class);
        when(b.isVectorAvailable()).thenReturn(vector);
        when(b.isFullTextAvailable()).thenReturn(fullText);
        return b;
    }

    @SuppressWarnings("unchecked")
    private CapturedQuery capture(JdbcTemplate jdbc) {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), args.capture());
        return new CapturedQuery(sql.getValue(), args.getValue());
    }

    private record CapturedQuery(String sql, Object[] args) {
        List<Object> argList() { return Arrays.asList(args); }
    }

    // -- branch 1: hybrid ----------------------------------------------------

    @Test
    void searchEvidence_hybrid_bindsClaimIdInBothArms_andRrfAndLimits() throws Exception {
        JdbcTemplate jdbc = jdbcReturningEmpty();
        HybridRetrievalService s = service(jdbc, boot(true, true));

        s.searchEvidence(42L, "right knee flexion", 8);

        CapturedQuery q = capture(jdbc);
        // The ONE hybrid SQL: FULL OUTER JOIN of the vec + txt CTEs.
        assertThat(q.sql()).contains("FULL OUTER JOIN");
        assertThat(q.sql()).contains("websearch_to_tsquery('english', ?)");
        assertThat(q.sql()).contains("embedding <=> CAST(? AS vector)");

        List<Object> args = q.argList();
        // scope bound twice (one per arm).
        assertThat(args).filteredOn("evidence"::equals).hasSize(2);
        // claim_id (42L) appears in BOTH arms: vec arm (= and IS NULL guard) + txt arm.
        long claimBindings = args.stream().filter(a -> Long.valueOf(42L).equals(a)).count();
        assertThat(claimBindings).isGreaterThanOrEqualTo(4);  // 2 per arm (= ? and ? IS NULL)
        // RRF k bound twice, candidate-k (40) for each arm LIMIT, final top-k (8, clamped ≤12).
        assertThat(args).filteredOn(Integer.valueOf(60)::equals).hasSize(2);
        assertThat(args).filteredOn(Integer.valueOf(40)::equals).hasSize(2);
        assertThat(args).contains(8);
        // A vector literal was bound (pgvector text format).
        assertThat(args).anyMatch(a -> a instanceof String str && str.startsWith("[") && str.endsWith("]"));
        // The query embedding was requested with the QUERY task type.
        assertThat(embedder.tasks).contains(EmbeddingProvider.TaskType.RETRIEVAL_QUERY);
    }

    @Test
    void searchKb_hybrid_bindsClaimIdNull_inBothArms() throws Exception {
        JdbcTemplate jdbc = jdbcReturningEmpty();
        HybridRetrievalService s = service(jdbc, boot(true, true));

        s.searchKb("diabetes presumptive herbicide", null, 5);

        CapturedQuery q = capture(jdbc);
        List<Object> args = q.argList();
        // scope = kb in both arms.
        assertThat(args).filteredOn("kb"::equals).hasSize(2);
        // No claim_id leaks: every claim_id binding is null (the (claim_id = ? OR ? IS NULL)
        // predicate is satisfied because the bound value is null).
        assertThat(args).doesNotContain(42L);
    }

    @Test
    void searchKb_withKbSource_bindsKbSourceFilterInBothArms() throws Exception {
        JdbcTemplate jdbc = jdbcReturningEmpty();
        HybridRetrievalService s = service(jdbc, boot(true, true));

        s.searchKb("flexion criteria", "vasrd", 5);

        CapturedQuery q = capture(jdbc);
        assertThat(q.sql()).contains("kb_source = ?");
        assertThat(q.argList()).filteredOn("vasrd"::equals).hasSize(2);  // one per arm
    }

    // -- branch 2: tsvector-only --------------------------------------------

    @Test
    void noVectorColumn_usesTsvectorOnlyBranch() throws Exception {
        JdbcTemplate jdbc = jdbcReturningEmpty();
        HybridRetrievalService s = service(jdbc, boot(false, true));

        s.searchEvidence(7L, "knee", 4);

        CapturedQuery q = capture(jdbc);
        assertThat(q.sql()).contains("ts_rank_cd");
        assertThat(q.sql()).doesNotContain("FULL OUTER JOIN");
        // No embedding requested on this path.
        assertThat(embedder.callCount).isZero();
        // claim_id still bound (isolation preserved in the degraded path).
        assertThat(q.argList()).contains(7L);
    }

    @Test
    void embeddingFailure_fallsBackToTsvectorOnly() throws Exception {
        JdbcTemplate jdbc = jdbcReturningEmpty();
        embedder.throwing(true);  // query embedding throws
        HybridRetrievalService s = service(jdbc, boot(true, true));

        s.searchEvidence(7L, "knee", 4);

        CapturedQuery q = capture(jdbc);
        assertThat(q.sql()).contains("ts_rank_cd");
        assertThat(q.sql()).doesNotContain("FULL OUTER JOIN");
    }

    // -- branch 3: naive ILIKE ----------------------------------------------

    @Test
    void noFullText_usesNaiveIlikeBranch() throws Exception {
        JdbcTemplate jdbc = jdbcReturningEmpty();
        HybridRetrievalService s = service(jdbc, boot(false, false));

        s.searchEvidence(9L, "knee", 4);

        CapturedQuery q = capture(jdbc);
        assertThat(q.sql()).contains("ILIKE");
        assertThat(q.argList()).contains("%knee%");
        assertThat(q.argList()).contains(9L);
    }

    // -- guards --------------------------------------------------------------

    @Test
    void searchEvidence_nullClaimId_throws() throws Exception {
        HybridRetrievalService s = service(jdbcReturningEmpty(), boot(true, true));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> s.searchEvidence(null, "q", 5));
    }

    @Test
    void blankQuery_shortCircuitsToEmpty_noSql() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        HybridRetrievalService s = service(jdbc, boot(true, true));
        assertThat(s.searchEvidence(1L, "   ", 5)).isEmpty();
        assertThat(s.searchKb("", null, 5)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void kClamp_neverExceedsTopK() throws Exception {
        JdbcTemplate jdbc = jdbcReturningEmpty();
        HybridRetrievalService s = service(jdbc, boot(true, true));
        s.searchEvidence(1L, "q", 999);  // request 999, top-k is 12
        assertThat(capture(jdbc).argList()).contains(12).doesNotContain(999);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = HybridRetrievalService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
