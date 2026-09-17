package com.afterduty.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Pre-public security review — authorization regressions.
 *
 * <p>Guards the fixes for the Tier-1 findings in
 * {@code docs/security/security-review-2026-06-01.md}:
 * <ul>
 *   <li>VCP-AUTHZ-01 / VCP-AUTHZ-03 — every {@code /api/claim/debug/**} route is admin-only
 *       (also closes the {@code getGapDetail} cross-tenant IDOR).</li>
 *   <li>VCP-AUTHZ-02 — (historical) scenario endpoints must not resolve another user's
 *       conditions; that CRUD surface was removed 2026-08-02 with the Flutter frontend.</li>
 *   <li>VCP-AUTHZ-04 — {@code GET /api/ai-costs/claim/{id}} is admin-only.</li>
 * </ul>
 * Auth uses the dev-mode {@code X-User-Email} header (local profile); admin status is keyed off
 * {@code va-claim.admin.emails} set below.
 */
@SpringBootTest
@ActiveProfiles("local")
@Tag("regression")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:authztest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.cloud.gcp.sql.enabled=false",
        "va-claim.admin.emails=admin-sec@afterduty.test",
        "spring.autoconfigure.exclude=com.google.cloud.spring.autoconfigure.sql.GcpCloudSqlAutoConfiguration,com.google.cloud.spring.autoconfigure.firestore.GcpFirestoreAutoConfiguration,com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration,com.google.cloud.spring.autoconfigure.secretmanager.GcpSecretManagerAutoConfiguration"
})
class SecurityAuthzRegressionTest {

    @Autowired WebApplicationContext context;
    @Autowired OncePerRequestFilter authFilter;

    MockMvc mvc;

    static final String ADMIN = "admin-sec@afterduty.test";
    static final String NON_ADMIN = "regular-sec@example.com";

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(authFilter).build();
    }

    // VCP-AUTHZ-02 note (2026-08-02): the saved-scenario CRUD this class used to probe
    // was removed with the Flutter frontend (docs/maintenance/dead-code-audit-2026-08-02.md)
    // — the cross-tenant resolution surface no longer exists. The owner-guard property
    // lives on in the per-condition mutations (exclude toggle, tested via its 403 path).

    // VCP-AUTHZ-01 / VCP-AUTHZ-03 — debug routes are admin-only. Non-admin is forbidden before the
    // controller even runs (so an enumerable id can't be probed); admin is not forbidden.
    // (Re-pointed 2026-08-02 from the removed GapAnalysisDebugController routes to the
    // kept IntakeController /debug/* surface — same matcher, same property.)
    @Test
    void debugAtoms_forbiddenForNonAdmin_allowedForAdmin() throws Exception {
        mvc.perform(get("/api/claim/debug/atoms").header("X-User-Email", NON_ADMIN))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/claim/debug/atoms").header("X-User-Email", ADMIN))
                .andExpect(status().isOk()); // reaches controller; empty claim → empty atom dump
    }

    @Test
    void debugPipelineTrigger_forbiddenForNonAdmin() throws Exception {
        mvc.perform(post("/api/claim/debug/synthesis")
                        .header("X-User-Email", NON_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // VCP-AUTHZ-04 — per-claim AI cost is admin-only (sibling endpoints already are).
    @Test
    void aiCostByClaim_forbiddenForNonAdmin_allowedForAdmin() throws Exception {
        mvc.perform(get("/api/ai-costs/claim/999999").header("X-User-Email", NON_ADMIN))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/ai-costs/claim/999999").header("X-User-Email", ADMIN))
                .andExpect(status().isOk());
    }

    // Passkeys P1.3 — the pre-session assert routes MUST be reachable WITHOUT a
    // Bearer/X-User-Email (they mint the session), exactly like email-code verify.
    // Reaching the controller (any non-401 status) proves they are exempt.
    @Test
    void webauthnAssertRoutes_arePublic_preSession() throws Exception {
        mvc.perform(post("/api/auth/webauthn/assert/options")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"ghost@example.com\"}"))
                .andExpect(status().is(not(equalTo(401))));

        // assert/verify with a bogus body reaches the controller and returns a
        // 400 (verification failed) — NOT a 401 (which would mean it was gated).
        mvc.perform(post("/api/auth/webauthn/assert/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":{}}"))
                .andExpect(status().isBadRequest());
    }

    // Passkeys P1.3 — the register + manage routes are NOT exempt: without auth
    // they must 401 (never reach the controller). This locks the exemption to
    // ONLY the two assert routes.
    @Test
    void webauthnRegisterAndManageRoutes_requireAuth() throws Exception {
        mvc.perform(post("/api/auth/webauthn/register/options"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/webauthn/credentials"))
                .andExpect(status().isUnauthorized());
    }
}
