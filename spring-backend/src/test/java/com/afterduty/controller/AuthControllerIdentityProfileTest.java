package com.afterduty.controller;

import com.jayway.jsonpath.JsonPath;
import com.afterduty.model.Atom;
import com.afterduty.repository.AtomRepository;
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

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Profile identity contract (pinned wire contracts):
 * <ul>
 *   <li>/auth/me never surfaces synthetic "&lt;uid&gt;@firebase.local" emails —
 *       the key is omitted entirely; real emails pass through.</li>
 *   <li>PATCH /auth/me sets preferredName (trimmed, 1..60, 400 outside) on the
 *       Bearer principal only — X-View-As is never honored.</li>
 *   <li>GET /auth/profile is now always 200 with servicePeriods[] (empty when
 *       nothing derivable), with document-derived and manual periods.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("local")
@Tag("regression")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:identityprofiletest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.cloud.gcp.sql.enabled=false",
        "spring.autoconfigure.exclude=com.google.cloud.spring.autoconfigure.sql.GcpCloudSqlAutoConfiguration,com.google.cloud.spring.autoconfigure.firestore.GcpFirestoreAutoConfiguration,com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration,com.google.cloud.spring.autoconfigure.secretmanager.GcpSecretManagerAutoConfiguration"
})
class AuthControllerIdentityProfileTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    OncePerRequestFilter authFilter;

    @Autowired
    AtomRepository atomRepository;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(authFilter)
                .build();
    }

    // -------------------------------------------------------------------------
    // Synthetic email nulling — /auth/me
    // -------------------------------------------------------------------------

    @Test
    void getMe_syntheticFirebaseLocalEmail_keyOmitted() throws Exception {
        mvc.perform(get("/api/auth/me")
                        .header("X-User-Email", "abc123uid@firebase.local"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.name").exists());
    }

    @Test
    void getMe_syntheticEmail_anyCase_keyOmitted() throws Exception {
        mvc.perform(get("/api/auth/me")
                        .header("X-User-Email", "XYZ789UID@Firebase.LOCAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void getMe_realEmail_passesThrough() throws Exception {
        mvc.perform(get("/api/auth/me")
                        .header("X-User-Email", "vet-identity@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("vet-identity@example.com"))
                // preferredName is serialized (null) even before the user sets one.
                .andExpect(jsonPath("$.preferredName").value(nullValue()));
    }

    // -------------------------------------------------------------------------
    // PATCH /auth/me — preferredName
    // -------------------------------------------------------------------------

    @Test
    void patchMe_setsTrimmedPreferredName_andMeSerializesIt() throws Exception {
        mvc.perform(patch("/api/auth/me")
                        .header("X-User-Email", "vet-pn@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferredName\":\"  Sam  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.preferredName").value("Sam"));

        mvc.perform(get("/api/auth/me")
                        .header("X-User-Email", "vet-pn@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredName").value("Sam"));
    }

    @Test
    void patchMe_validation_missingBlankAndTooLongAre400() throws Exception {
        mvc.perform(patch("/api/auth/me")
                        .header("X-User-Email", "vet-pn2@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mvc.perform(patch("/api/auth/me")
                        .header("X-User-Email", "vet-pn2@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferredName\":\"   \"}"))
                .andExpect(status().isBadRequest());

        String tooLong = "x".repeat(61);
        mvc.perform(patch("/api/auth/me")
                        .header("X-User-Email", "vet-pn2@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferredName\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());

        // Boundary: exactly 60 chars is accepted.
        String max = "y".repeat(60);
        mvc.perform(patch("/api/auth/me")
                        .header("X-User-Email", "vet-pn2@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferredName\":\"" + max + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredName").value(max));
    }

    @Test
    void patchMe_neverHonorsViewAs_mutatesCallerOnly() throws Exception {
        // Owner gets a claim (auto-created by /me) — the X-View-As target.
        String ownerMe = mvc.perform(get("/api/auth/me")
                        .header("X-User-Email", "owner-pn@example.com"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Integer ownerClaimId = JsonPath.parse(ownerMe).read("$.activeClaim.id");

        // Caller PATCHes with X-View-As pointing at the owner's claim: the
        // mutation must land on the CALLER's account (account-level, Bearer
        // principal only) and must not error or touch the owner.
        mvc.perform(patch("/api/auth/me")
                        .header("X-User-Email", "viewer-pn@example.com")
                        .header("X-View-As", String.valueOf(ownerClaimId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferredName\":\"Viewer Vic\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredName").value("Viewer Vic"));

        mvc.perform(get("/api/auth/me")
                        .header("X-User-Email", "viewer-pn@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredName").value("Viewer Vic"));

        mvc.perform(get("/api/auth/me")
                        .header("X-User-Email", "owner-pn@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredName").value(nullValue()));
    }

    // -------------------------------------------------------------------------
    // GET /auth/profile — always 200 with servicePeriods
    // -------------------------------------------------------------------------

    @Test
    void getProfile_noManualRow_returns200WithEmptyServicePeriods() throws Exception {
        mvc.perform(get("/api/auth/profile")
                        .header("X-User-Email", "fresh-profile@example.com"))
                .andExpect(status().isOk())
                // No manual row: legacy fields are null (omitted per the app-wide
                // non_null jackson default) but servicePeriods is always present.
                .andExpect(jsonPath("$.branch").doesNotExist())
                .andExpect(jsonPath("$.servicePeriods").isArray())
                .andExpect(jsonPath("$.servicePeriods.length()").value(0));
    }

    @Test
    void getProfile_manualRowOnly_appearsAsManualPeriod() throws Exception {
        mvc.perform(post("/api/auth/profile")
                        .header("X-User-Email", "manual-profile@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branch\":\"Army National Guard\",\"serviceStart\":\"2006-01-10\"," +
                                "\"serviceEnd\":\"2012-03-01\",\"mos\":\"11B\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/auth/profile")
                        .header("X-User-Email", "manual-profile@example.com"))
                .andExpect(status().isOk())
                // Existing fields unchanged.
                .andExpect(jsonPath("$.branch").value("Army National Guard"))
                .andExpect(jsonPath("$.servicePeriods.length()").value(1))
                .andExpect(jsonPath("$.servicePeriods[0].source").value("manual"))
                .andExpect(jsonPath("$.servicePeriods[0].branch").value("Army National Guard"))
                .andExpect(jsonPath("$.servicePeriods[0].component").value("guard"))
                .andExpect(jsonPath("$.servicePeriods[0].startDate").value("2006-01-10"))
                .andExpect(jsonPath("$.servicePeriods[0].endDate").value("2012-03-01"))
                .andExpect(jsonPath("$.servicePeriods[0].mos").value("11B"))
                .andExpect(jsonPath("$.servicePeriods[0].rank").value(nullValue()));
    }

    @Test
    void getProfile_documentAtoms_deriveDocumentsPeriod_evenWithoutManualRow() throws Exception {
        // /me auto-creates the claim; extraction would then persist atoms on it.
        String me = mvc.perform(get("/api/auth/me")
                        .header("X-User-Email", "doc-profile@example.com"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Integer claimId = JsonPath.parse(me).read("$.activeClaim.id");

        saveServiceRecordAtom(claimId, "Branch of Service: Army");
        saveServiceRecordAtom(claimId, "Rank: SGT");
        saveServiceRecordAtom(claimId, "MOS/Rating/AFSC: 11B");
        saveServiceRecordAtom(claimId,
                "Service Period: Enlistment: 2001-05-14 | Separation: 2005-08-30 | Total Years: 4");

        mvc.perform(get("/api/auth/profile")
                        .header("X-User-Email", "doc-profile@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicePeriods.length()").value(1))
                .andExpect(jsonPath("$.servicePeriods[0].source").value("documents"))
                .andExpect(jsonPath("$.servicePeriods[0].branch").value("Army"))
                .andExpect(jsonPath("$.servicePeriods[0].component").value("active"))
                .andExpect(jsonPath("$.servicePeriods[0].startDate").value("2001-05-14"))
                .andExpect(jsonPath("$.servicePeriods[0].endDate").value("2005-08-30"))
                .andExpect(jsonPath("$.servicePeriods[0].mos").value("11B"))
                .andExpect(jsonPath("$.servicePeriods[0].rank").value("SGT"));
    }

    private void saveServiceRecordAtom(Integer claimId, String value) {
        atomRepository.save(Atom.builder()
                .claimId(claimId.longValue())
                .type("service_record_detail")
                .value(value)
                .source("dd214.pdf")
                .createdBy("ai:extraction-service-record")
                .build());
    }
}
