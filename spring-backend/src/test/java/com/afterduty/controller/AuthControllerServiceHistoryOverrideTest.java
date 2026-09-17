package com.afterduty.controller;

import com.jayway.jsonpath.JsonPath;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Service History P3 Part A — the veteran-override endpoint
 * ({@code POST/DELETE /api/auth/service-history/override}). Verifies: upsert applies
 * at top authority ("Corrected by you" + corrected value), delete reverts, validation
 * (400), and the OWNER GUARD — one account's override never touches another's, and
 * X-View-As is never honored (account-level mutation on the Bearer principal only).
 */
@SpringBootTest
@ActiveProfiles("local")
@Tag("regression")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:svchistoryoverridetest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.cloud.gcp.sql.enabled=false",
        "spring.autoconfigure.exclude=com.google.cloud.spring.autoconfigure.sql.GcpCloudSqlAutoConfiguration,com.google.cloud.spring.autoconfigure.firestore.GcpFirestoreAutoConfiguration,com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration,com.google.cloud.spring.autoconfigure.secretmanager.GcpSecretManagerAutoConfiguration"
})
class AuthControllerServiceHistoryOverrideTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    OncePerRequestFilter authFilter;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(authFilter).build();
    }

    /** Seed a manual Navy profile for the user, returning the derived conclusion's
     *  stable clusterKey (canonicalBranch|component|startYear). */
    private String seedProfileAndGetClusterKey(String email) throws Exception {
        mvc.perform(post("/api/auth/profile")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branch\":\"US Navy\",\"serviceStart\":\"2001-06-01\"," +
                                "\"serviceEnd\":\"2009-06-01\",\"mos\":\"IT\"}"))
                .andExpect(status().isOk());

        String profile = mvc.perform(get("/api/auth/profile").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(profile).read("$.servicePeriods[0].clusterKey");
    }

    @Test
    void upsert_appliesOverrideAtTopAuthority_andReturnsCorrectedPeriods() throws Exception {
        String key = seedProfileAndGetClusterKey("ov-owner@example.com");

        mvc.perform(post("/api/auth/service-history/override")
                        .header("X-User-Email", "ov-owner@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clusterKey\":\"" + key + "\",\"endDate\":\"2010-06-01\",\"rank\":\"CPO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.servicePeriods[0].endDate").value("2010-06-01"))
                .andExpect(jsonPath("$.servicePeriods[0].rank").value("CPO"))
                .andExpect(jsonPath("$.servicePeriods[0].reasoning").value(
                        org.hamcrest.Matchers.containsString("Corrected by you")));

        // The correction persists on a fresh profile read.
        mvc.perform(get("/api/auth/profile").header("X-User-Email", "ov-owner@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicePeriods[0].endDate").value("2010-06-01"))
                .andExpect(jsonPath("$.servicePeriods[0].rank").value("CPO"));
    }

    @Test
    void upsert_isIdempotent_secondCallReplacesFields() throws Exception {
        String key = seedProfileAndGetClusterKey("ov-upsert@example.com");

        mvc.perform(post("/api/auth/service-history/override")
                        .header("X-User-Email", "ov-upsert@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clusterKey\":\"" + key + "\",\"endDate\":\"2010-06-01\"}"))
                .andExpect(status().isOk());
        // Second upsert with a different value replaces (not duplicates).
        mvc.perform(post("/api/auth/service-history/override")
                        .header("X-User-Email", "ov-upsert@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clusterKey\":\"" + key + "\",\"endDate\":\"2011-06-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicePeriods[0].endDate").value("2011-06-01"));
    }

    @Test
    void delete_clearsOverride_revertingToReconciledValue() throws Exception {
        String key = seedProfileAndGetClusterKey("ov-del@example.com");

        mvc.perform(post("/api/auth/service-history/override")
                        .header("X-User-Email", "ov-del@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clusterKey\":\"" + key + "\",\"endDate\":\"2010-06-01\"}"))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/auth/service-history/override/{k}", key)
                        .header("X-User-Email", "ov-del@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                // Reverted to the manual/reconciled end date.
                .andExpect(jsonPath("$.servicePeriods[0].endDate").value("2009-06-01"));
    }

    @Test
    void delete_isIdempotent_forUnknownCluster() throws Exception {
        seedProfileAndGetClusterKey("ov-del2@example.com");
        mvc.perform(delete("/api/auth/service-history/override/{k}", "Navy|active|9999")
                        .header("X-User-Email", "ov-del2@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    // -------------------------------------------------------------------------
    // OWNER GUARD — cross-account isolation
    // -------------------------------------------------------------------------

    @Test
    void override_isOwnerScoped_oneAccountNeverTouchesAnothers() throws Exception {
        // Two users each seed the SAME shape → the SAME clusterKey string.
        String keyA = seedProfileAndGetClusterKey("iso-a@example.com");
        String keyB = seedProfileAndGetClusterKey("iso-b@example.com");
        org.assertj.core.api.Assertions.assertThat(keyA).isEqualTo(keyB); // identical key, different owners

        // User A overrides the rank.
        mvc.perform(post("/api/auth/service-history/override")
                        .header("X-User-Email", "iso-a@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clusterKey\":\"" + keyA + "\",\"rank\":\"CPO\"}"))
                .andExpect(status().isOk());

        // User B's profile is UNAFFECTED (no rank override) — the clusterKey is data,
        // the userId is the resolved principal.
        mvc.perform(get("/api/auth/profile").header("X-User-Email", "iso-b@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicePeriods[0].rank").value(org.hamcrest.Matchers.nullValue()));

        // User B deleting "their" override does NOT clear User A's.
        mvc.perform(delete("/api/auth/service-history/override/{k}", keyB)
                        .header("X-User-Email", "iso-b@example.com"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/auth/profile").header("X-User-Email", "iso-a@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicePeriods[0].rank").value("CPO")); // A still corrected
    }

    @Test
    void override_neverHonorsViewAs_writesToBearerPrincipalOnly() throws Exception {
        // Owner has a claim (the X-View-As target).
        String ownerMe = mvc.perform(get("/api/auth/me")
                        .header("X-User-Email", "va-owner@example.com"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Integer ownerClaimId = JsonPath.parse(ownerMe).read("$.activeClaim.id");

        String viewerKey = seedProfileAndGetClusterKey("va-viewer@example.com");
        // Viewer POSTs an override with X-View-As pointing at the owner's claim: it
        // must land on the VIEWER's own account, never the owner's.
        mvc.perform(post("/api/auth/service-history/override")
                        .header("X-User-Email", "va-viewer@example.com")
                        .header("X-View-As", String.valueOf(ownerClaimId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clusterKey\":\"" + viewerKey + "\",\"rank\":\"SR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicePeriods[0].rank").value("SR"));

        // Owner is untouched (has no manual profile / no override at all).
        mvc.perform(get("/api/auth/profile").header("X-User-Email", "va-owner@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicePeriods.length()").value(0));
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    @Test
    void upsert_missingClusterKey_is400() throws Exception {
        mvc.perform(post("/api/auth/service-history/override")
                        .header("X-User-Email", "val-1@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\":\"2010-06-01\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upsert_noFieldsSet_is400() throws Exception {
        mvc.perform(post("/api/auth/service-history/override")
                        .header("X-User-Email", "val-2@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clusterKey\":\"Navy|active|2001\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upsert_badComponentAndBadDate_are400() throws Exception {
        mvc.perform(post("/api/auth/service-history/override")
                        .header("X-User-Email", "val-3@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clusterKey\":\"Navy|active|2001\",\"component\":\"parttime\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/auth/service-history/override")
                        .header("X-User-Email", "val-3@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clusterKey\":\"Navy|active|2001\",\"startDate\":\"June 2001\"}"))
                .andExpect(status().isBadRequest());
    }
}
