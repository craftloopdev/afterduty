package com.afterduty.controller;

import com.afterduty.model.Claim;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.model.User;
import com.afterduty.model.UserGapState;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.ConditionRepository;
import com.afterduty.repository.UserGapStateRepository;
import com.afterduty.repository.UserRepository;
import com.afterduty.service.gap.UserGapStateService;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1-6 contract test — PATCH /api/claim/gaps/{condId}/{gapIndex}/status.
 *
 * Pins:
 *  - 200 {"ok": true, "status": s} for resolved/dismissed/open;
 *  - the status is stamped into the stored gap JSON so GET /gaps reflects it
 *    IMMEDIATELY (no re-run needed);
 *  - a durable user_gap_state row is upserted, keyed by the condition's
 *    identity fingerprint + the gap's (type, triad_leg) — one row per key no
 *    matter how many times the status flips;
 *  - 404 for a condition that doesn't exist, isn't the caller's, is
 *    superseded, or an index outside the gap list; 400 for a bogus status;
 *  - predicate unification: /jobs' open_gaps counter and /gaps' serialized
 *    status share UserGapStateService's ONE open-gap predicate, so the two
 *    endpoints always agree.
 */
@SpringBootTest
@ActiveProfiles("local")
@Tag("regression")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:gapstatuspatchtest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.cloud.gcp.sql.enabled=false",
        "spring.autoconfigure.exclude=com.google.cloud.spring.autoconfigure.sql.GcpCloudSqlAutoConfiguration,com.google.cloud.spring.autoconfigure.firestore.GcpFirestoreAutoConfiguration,com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration,com.google.cloud.spring.autoconfigure.secretmanager.GcpSecretManagerAutoConfiguration"
})
class IntakeControllerGapStatusPatchTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    OncePerRequestFilter authFilter;

    @Autowired
    ClaimRepository claimRepository;

    @Autowired
    ConditionRepository conditionRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    UserGapStateRepository userGapStateRepository;

    @Autowired
    UserGapStateService userGapStateService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(authFilter)
                .build();
    }

    /** Auto-creates the user + claim via the API and returns the claim id. */
    private long claimFor(String email) throws Exception {
        mvc.perform(get("/api/claim").header("X-User-Email", email))
                .andExpect(status().isOk());
        User u = userRepository.findByEmail(email.toLowerCase())
                .or(() -> userRepository.findByEmail(email))
                .orElseThrow();
        return claimRepository.findByUserIdOrderByCreatedAtDesc(u.getId()).get(0).getId();
    }

    /** A gap map shaped exactly as the live pipeline persists it. */
    private Map<String, Object> pipelineGap(String type, String triadLeg, String title) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("type", type);
        g.put("title", title);
        g.put("description", "desc for " + title);
        g.put("triad_leg", triadLeg);
        g.put("priority", "high");
        g.put("rating_impact", "+20%");
        g.put("how_to_get_it", "Ask the provider.");
        return g;
    }

    private IdentifiedCondition saveCondition(long claimId, String name,
                                              List<Map<String, Object>> gaps) {
        return conditionRepository.save(IdentifiedCondition.builder()
                .claimId(claimId)
                .name(name)
                .vasrdCode("9411")
                .gaps(gaps)
                .build());
    }

    private IdentifiedCondition twoGapCondition(long claimId) {
        return saveCondition(claimId, "PTSD", List.of(
                pipelineGap("nexus_letter", "nexus", "Obtain nexus letter"),
                pipelineGap("buddy_statement", null, "Collect buddy statements")));
    }

    @Test
    void patchResolved_returnsContract_gapsReflectsImmediately_rowUpserted() throws Exception {
        String email = "gap-patch-resolve@example.com";
        long claimId = claimFor(email);
        IdentifiedCondition cond = twoGapCondition(claimId);

        mvc.perform(patch("/api/claim/gaps/" + cond.getId() + "/0/status")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"resolved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.status").value("resolved"));

        // /gaps reflects the new status immediately; the untouched gap stays open.
        mvc.perform(get("/api/claim/gaps").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gaps[0].status").value("resolved"))
                .andExpect(jsonPath("$.gaps[1].status").value("open"));

        // Durable row keyed by fingerprint + (type, triad_leg).
        IdentifiedCondition reloaded = conditionRepository.findById(cond.getId()).orElseThrow();
        String fp = userGapStateService.fingerprintFor(reloaded);
        UserGapState row = userGapStateRepository
                .findByClaimIdAndIdentityFingerprintAndGapTypeAndTriadLeg(
                        claimId, fp, "nexus_letter", "nexus")
                .orElseThrow();
        assertEquals("resolved", row.getStatus());
    }

    @Test
    void patchTwice_upsertsSingleRow_lastStatusWins() throws Exception {
        String email = "gap-patch-upsert@example.com";
        long claimId = claimFor(email);
        IdentifiedCondition cond = twoGapCondition(claimId);

        mvc.perform(patch("/api/claim/gaps/" + cond.getId() + "/1/status")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"dismissed\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/claim/gaps/" + cond.getId() + "/1/status")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"open\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("open"));

        // Null-leg gap: exactly ONE row for the key, status = last write.
        IdentifiedCondition reloaded = conditionRepository.findById(cond.getId()).orElseThrow();
        String fp = userGapStateService.fingerprintFor(reloaded);
        List<UserGapState> rows =
                userGapStateRepository.findByClaimIdAndIdentityFingerprint(claimId, fp);
        assertEquals(1, rows.size(), "upsert must never duplicate the (fp, type, leg) key");
        assertEquals("open", rows.get(0).getStatus());
        assertNull(rows.get(0).getTriadLeg());

        mvc.perform(get("/api/claim/gaps").header("X-User-Email", email))
                .andExpect(jsonPath("$.gaps[1].status").value("open"));
    }

    @Test
    void openGapPredicate_jobsAndGapsAgree() throws Exception {
        String email = "gap-patch-predicate@example.com";
        long claimId = claimFor(email);
        IdentifiedCondition cond = twoGapCondition(claimId);

        // Both open initially.
        mvc.perform(get("/api/claim/jobs").header("X-User-Email", email))
                .andExpect(jsonPath("$.summary.open_gaps").value(2));

        mvc.perform(patch("/api/claim/gaps/" + cond.getId() + "/0/status")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"resolved\"}"))
                .andExpect(status().isOk());

        // /jobs counts 1 open; /gaps serializes exactly one non-open status.
        mvc.perform(get("/api/claim/jobs").header("X-User-Email", email))
                .andExpect(jsonPath("$.summary.open_gaps").value(1));
        mvc.perform(get("/api/claim/gaps").header("X-User-Email", email))
                .andExpect(jsonPath("$.gaps[0].status").value("resolved"))
                .andExpect(jsonPath("$.gaps[1].status").value("open"));

        mvc.perform(patch("/api/claim/gaps/" + cond.getId() + "/1/status")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"dismissed\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/claim/jobs").header("X-User-Email", email))
                .andExpect(jsonPath("$.summary.open_gaps").value(0));
    }

    @Test
    void unknownCondition_is404() throws Exception {
        String email = "gap-patch-nocond@example.com";
        claimFor(email);

        mvc.perform(patch("/api/claim/gaps/999999/0/status")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"resolved\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void indexOutOfRange_is404() throws Exception {
        String email = "gap-patch-badindex@example.com";
        long claimId = claimFor(email);
        IdentifiedCondition cond = twoGapCondition(claimId);

        mvc.perform(patch("/api/claim/gaps/" + cond.getId() + "/2/status")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"resolved\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherUsersCondition_is404_notLeaked() throws Exception {
        String owner = "gap-patch-owner@example.com";
        String attacker = "gap-patch-attacker@example.com";
        long ownerClaimId = claimFor(owner);
        IdentifiedCondition cond = twoGapCondition(ownerClaimId);
        claimFor(attacker);

        mvc.perform(patch("/api/claim/gaps/" + cond.getId() + "/0/status")
                        .header("X-User-Email", attacker)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"dismissed\"}"))
                .andExpect(status().isNotFound());

        // The owner's gap is untouched.
        mvc.perform(get("/api/claim/gaps").header("X-User-Email", owner))
                .andExpect(jsonPath("$.gaps[0].status").value("open"));
    }

    @Test
    void supersededCondition_is404() throws Exception {
        String email = "gap-patch-superseded@example.com";
        long claimId = claimFor(email);
        IdentifiedCondition active = twoGapCondition(claimId);
        IdentifiedCondition old = saveCondition(claimId, "PTSD (old gen)",
                List.of(pipelineGap("nexus_letter", "nexus", "Old-gen gap")));
        old.setSupersededBy(active.getId());
        conditionRepository.save(old);

        mvc.perform(patch("/api/claim/gaps/" + old.getId() + "/0/status")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"resolved\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidStatus_is400() throws Exception {
        String email = "gap-patch-badstatus@example.com";
        long claimId = claimFor(email);
        IdentifiedCondition cond = twoGapCondition(claimId);

        mvc.perform(patch("/api/claim/gaps/" + cond.getId() + "/0/status")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"done\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/claim/gaps/" + cond.getId() + "/0/status")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
