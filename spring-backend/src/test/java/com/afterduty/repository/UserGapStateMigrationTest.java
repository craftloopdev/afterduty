package com.afterduty.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-6 — V20260702_2__user_gap_state.sql applies cleanly on an embedded
 * PostgreSQL-mode H2 database (the project's test-parity target: Flyway is
 * not wired, so migrations are the canonical SQL kept in lockstep with the
 * entity, and this test proves the SQL itself is valid on both dialect
 * families the app runs against).
 *
 * Also pins the constraint semantics the service layer relies on:
 *  - the (claim_id, identity_fingerprint, gap_type, triad_leg) key rejects
 *    exact duplicates;
 *  - NULL triad_leg rows are NOT deduped by the DB (SQL NULLs are distinct) —
 *    documenting why UserGapStateService.upsert must be the null-leg guard.
 */
@Tag("regression")
class UserGapStateMigrationTest {

    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection(
                "jdbc:h2:mem:usergapstatemigration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement st = conn.createStatement()) {
            // Prerequisite from earlier migrations: the claims table the new
            // table's FK references (minimal stub — Flyway would have run the
            // full chain before this file).
            st.execute("CREATE TABLE claims (id BIGSERIAL PRIMARY KEY)");
            st.execute("INSERT INTO claims (id) VALUES (1)");
        }
        ScriptUtils.executeSqlScript(conn,
                new ClassPathResource("db/migration/V20260702_2__user_gap_state.sql"));
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP ALL OBJECTS");
        }
        conn.close();
    }

    private void insert(String fp, String gapType, String triadLeg, String status) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO user_gap_state (claim_id, identity_fingerprint, gap_type, triad_leg, status, updated_at) "
                        + "VALUES (1, ?, ?, ?, ?, NOW())")) {
            ps.setString(1, fp);
            ps.setString(2, gapType);
            ps.setString(3, triadLeg);
            ps.setString(4, status);
            ps.executeUpdate();
        }
    }

    private long count() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM user_gap_state")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void migrationApplies_insertAndReadBack() throws Exception {
        insert("fp-1", "nexus_letter", "nexus", "resolved");
        insert("fp-1", "buddy_statement", null, "dismissed");
        assertEquals(2, count());

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT status, note FROM user_gap_state WHERE gap_type = 'nexus_letter'")) {
            assertTrue(rs.next());
            assertEquals("resolved", rs.getString("status"));
            assertNull(rs.getString("note"), "note is nullable and defaults to NULL");
        }
    }

    @Test
    void uniqueKey_rejectsExactDuplicate_nonNullLeg() throws Exception {
        insert("fp-1", "nexus_letter", "nexus", "resolved");
        assertThrows(SQLException.class,
                () -> insert("fp-1", "nexus_letter", "nexus", "dismissed"),
                "same (claim, fingerprint, type, leg) must violate uq_user_gap_state_key");

        // A different leg (or fingerprint) is a different key.
        insert("fp-1", "nexus_letter", "severity", "resolved");
        insert("fp-2", "nexus_letter", "nexus", "resolved");
        assertEquals(3, count());
    }

    @Test
    void nullLeg_duplicatesAllowedByDb_serviceUpsertIsTheGuard() throws Exception {
        // SQL UNIQUE treats NULLs as distinct — documented behavior the
        // app-level find-then-save upsert compensates for.
        insert("fp-1", "buddy_statement", null, "dismissed");
        insert("fp-1", "buddy_statement", null, "open");
        assertEquals(2, count());
    }
}
