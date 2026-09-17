package com.afterduty.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Guarded pgvector / full-text bootstrap (spec §A.3, §H.1). Mock {@link JdbcTemplate}:
 *
 * <ul>
 *   <li>extension fails ⇒ {@code vectorAvailable=false}, step 2 skipped, step 3 still
 *       attempted, full-text still available;</li>
 *   <li>extension succeeds ⇒ all DDL issued, both flags true;</li>
 *   <li>every statement is an {@code IF NOT EXISTS} literal — boot is idempotent.</li>
 * </ul>
 *
 * <p>The pgvector SQL itself is Postgres-only and cannot run on H2 — see the §H.4 deployed
 * smoke checklist for the live {@code CREATE EXTENSION} / {@code \d chunks} verification.
 */
@Tag("regression")
class PgVectorBootstrapTest {

    @Test
    void extensionSucceeds_issuesAllDdl_andBothFlagsTrue() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doNothing().when(jdbc).execute(anyString());

        PgVectorBootstrap boot = new PgVectorBootstrap(jdbc);
        boot.bootstrap();

        List<String> sql = capturedSql(jdbc, 5);
        // Step 1: the extension.
        assertThat(sql.get(0)).isEqualTo("CREATE EXTENSION IF NOT EXISTS vector");
        // Step 2: vector column + HNSW.
        assertThat(sql).anyMatch(s -> s.contains("ADD COLUMN IF NOT EXISTS embedding vector(768)"));
        assertThat(sql).anyMatch(s -> s.contains("USING hnsw (embedding vector_cosine_ops)")
                && s.contains("IF NOT EXISTS"));
        // Step 3: generated tsvector column + GIN.
        assertThat(sql).anyMatch(s -> s.contains("ADD COLUMN IF NOT EXISTS tsv tsvector")
                && s.contains("GENERATED ALWAYS AS"));
        assertThat(sql).anyMatch(s -> s.contains("USING gin (tsv)") && s.contains("IF NOT EXISTS"));

        assertThat(boot.isVectorAvailable()).isTrue();
        assertThat(boot.isFullTextAvailable()).isTrue();
    }

    @Test
    void extensionFails_degradesToFullTextOnly_skipsVectorDdl_stillAttemptsTsvector() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // Extension call throws; the rest succeed.
        doThrow(new RuntimeException("extension \"vector\" is not available"))
                .when(jdbc).execute("CREATE EXTENSION IF NOT EXISTS vector");

        PgVectorBootstrap boot = new PgVectorBootstrap(jdbc);
        boot.bootstrap();

        List<String> sql = capturedSql(jdbc, 1);  // at least the extension attempt
        // Vector column / HNSW must NOT have been attempted.
        assertThat(sql).noneMatch(s -> s.contains("embedding vector(768)"));
        assertThat(sql).noneMatch(s -> s.contains("hnsw"));
        // tsvector step is still attempted (always).
        assertThat(sql).anyMatch(s -> s.contains("tsv tsvector"));

        assertThat(boot.isVectorAvailable()).isFalse();
        assertThat(boot.isFullTextAvailable()).isTrue();
    }

    @Test
    void vectorColumnDdlFails_afterExtension_degradesToFullTextOnly() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doThrow(new RuntimeException("permission denied to create index"))
                .when(jdbc).execute(org.mockito.ArgumentMatchers.contains("hnsw"));

        PgVectorBootstrap boot = new PgVectorBootstrap(jdbc);
        boot.bootstrap();

        // Extension reported success, but index DDL failed → vector unavailable,
        // full-text still up.
        assertThat(boot.isVectorAvailable()).isFalse();
        assertThat(boot.isFullTextAvailable()).isTrue();
    }

    @Test
    void tsvectorFails_degradesToNaiveIlike() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doThrow(new RuntimeException("syntax error near tsvector"))
                .when(jdbc).execute(org.mockito.ArgumentMatchers.contains("tsv tsvector"));

        PgVectorBootstrap boot = new PgVectorBootstrap(jdbc);
        boot.bootstrap();

        assertThat(boot.isVectorAvailable()).isTrue();
        assertThat(boot.isFullTextAvailable()).isFalse();
    }

    @Test
    void allStatementsAreIdempotentIfNotExistsLiterals() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doNothing().when(jdbc).execute(anyString());

        PgVectorBootstrap boot = new PgVectorBootstrap(jdbc);
        boot.bootstrap();

        // Every DDL statement carries IF NOT EXISTS so a repeated boot is harmless.
        for (String s : capturedSql(jdbc, 5)) {
            assertThat(s).containsIgnoringCase("IF NOT EXISTS");
        }
    }

    private static List<String> capturedSql(JdbcTemplate jdbc, int min) {
        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeast(min)).execute(cap.capture());
        return cap.getAllValues();
    }
}
