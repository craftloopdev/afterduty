package com.afterduty.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-14 — V20260702_4__condition_suppressions.sql applies cleanly on an embedded
 * PostgreSQL-mode H2 database (the project's test-parity target: Flyway is not wired,
 * so migrations are the canonical SQL kept in lockstep with the ConditionSuppression
 * entity, and this test proves the SQL itself is valid on both dialect families),
 * following the UserGapStateMigrationTest pattern.
 */
@Tag("regression")
class ConditionSuppressionMigrationTest {

    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection(
                "jdbc:h2:mem:conditionsuppressionmigration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement st = conn.createStatement()) {
            // Prerequisite from earlier migrations: the claims table the FK references.
            st.execute("CREATE TABLE claims (id BIGSERIAL PRIMARY KEY)");
            st.execute("INSERT INTO claims (id) VALUES (1)");
        }
        ScriptUtils.executeSqlScript(conn,
                new ClassPathResource("db/migration/V20260702_4__condition_suppressions.sql"));
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
        conn.close();
    }

    @Test
    void migrationApplies_andAcceptsTheChatDeletePathRow() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("INSERT INTO condition_suppressions "
                    + "(claim_id, condition_id, identity_fingerprint, condition_name, reason, "
                    + " created_by_user_id, created_at) "
                    + "VALUES (1, 11, 'fp-knee', 'Knee strain', 'veteran said not applicable', 5, now())");
            try (ResultSet rs = st.executeQuery(
                    "SELECT identity_fingerprint, lifted_at FROM condition_suppressions WHERE claim_id = 1")) {
                assertTrue(rs.next(), "the inserted suppression row must be readable");
                assertEquals("fp-knee", rs.getString(1));
                rs.getTimestamp(2);
                assertTrue(rs.wasNull(), "lifted_at defaults to NULL (unlifted = actively filtering)");
            }
        }
    }

    @Test
    void identityFingerprint_isNullable_forLegacyRows() throws Exception {
        try (Statement st = conn.createStatement()) {
            // Legacy/flag-off rows have no fingerprint; the suppression row must still insert
            // (the sentinel in identified_conditions.superseded_by does the hiding for them).
            st.execute("INSERT INTO condition_suppressions (claim_id, condition_id, created_at) "
                    + "VALUES (1, 12, now())");
            try (ResultSet rs = st.executeQuery(
                    "SELECT count(*) FROM condition_suppressions WHERE identity_fingerprint IS NULL")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }
}
