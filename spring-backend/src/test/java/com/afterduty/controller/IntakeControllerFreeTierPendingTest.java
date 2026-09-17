package com.afterduty.controller;

import com.afterduty.model.Claim;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.model.User;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.ConditionRepository;
import com.afterduty.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Item D (2026-07-01 review §6, Phase D) — with
 * {@code va-claim.subscription.free-analysis-tier=a1} the gap stage NEVER runs
 * for a free claim, so its conditions carry {@code gaps == null} permanently.
 * That state must NOT read as the P0-5 "re-checking your next steps"
 * transient: {@code GET /gaps} must return an empty list with
 * {@code gapAnalysisPending=false}, and {@code GET /jobs} must report
 * {@code gap_analysis_pending=false} — otherwise the web shows a free user an
 * eternal "re-checking" instead of the Pro upsell.
 *
 * <p>For PRO owners the P0-5 semantics are unchanged: null gaps ⇒ pending=true.
 * (Flag-off behavior for everyone is pinned by
 * {@link IntakeControllerGapsContractTest} — null gaps ⇒ pending=true — and
 * must keep passing untouched.)
 */
@SpringBootTest
@ActiveProfiles("local")
@Tag("regression")
@TestPropertySource(properties = {
        "va-claim.subscription.free-analysis-tier=a1",
        "spring.datasource.url=jdbc:h2:mem:intakefreetiertest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.cloud.gcp.sql.enabled=false",
        "spring.autoconfigure.exclude=com.google.cloud.spring.autoconfigure.sql.GcpCloudSqlAutoConfiguration,com.google.cloud.spring.autoconfigure.firestore.GcpFirestoreAutoConfiguration,com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration,com.google.cloud.spring.autoconfigure.secretmanager.GcpSecretManagerAutoConfiguration"
})
class IntakeControllerFreeTierPendingTest {

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
        return claimRepository.findByUserIdOrderByCreatedAtDesc(userOf(email).getId())
                .get(0).getId();
    }

    private User userOf(String email) {
        return userRepository.findByEmail(email.toLowerCase())
                .or(() -> userRepository.findByEmail(email))
                .orElseThrow();
    }

    private void makePro(String email) {
        User u = userOf(email);
        u.setSubscriptionExpiresAt(Instant.now().plusSeconds(365L * 24 * 3600));
        userRepository.save(u);
    }

    private void saveCondition(long claimId, String name, List<Map<String, Object>> gaps) {
        conditionRepository.save(IdentifiedCondition.builder()
                .claimId(claimId)
                .name(name)
                .gaps(gaps)
                .build());
    }

    private Map<String, Object> pipelineGap() {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("type", "nexus_letter");
        g.put("title", "Obtain nexus letter");
        g.put("description", "Nexus not documented.");
        g.put("how_to_get_it", "Ask your treating clinician.");
        g.put("rating_impact", "+20%");
        return g;
    }

    @Test
    void freeOwner_nullGapConditions_notPending_onGapsAndJobs() throws Exception {
        String email = "free-a1@example.com";
        long claimId = claimFor(email);
        // The permanent free-A1 shape: synthesis wrote conditions, gap never ran.
        saveCondition(claimId, "PTSD", null);
        saveCondition(claimId, "Tinnitus", null);

        mvc.perform(get("/api/claim/gaps").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gaps", hasSize(0)))
                .andExpect(jsonPath("$.gapAnalysisPending").value(false));

        mvc.perform(get("/api/claim/jobs").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gap_analysis_pending").value(false))
                .andExpect(jsonPath("$.summary.conditions").value(2))
                .andExpect(jsonPath("$.summary.open_gaps").value(0));
    }

    @Test
    void proOwner_nullGapCondition_stillPending_p05Preserved() throws Exception {
        String email = "pro-a1@example.com";
        long claimId = claimFor(email);
        makePro(email);
        saveCondition(claimId, "PTSD", null);

        mvc.perform(get("/api/claim/gaps").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gapAnalysisPending").value(true));

        mvc.perform(get("/api/claim/jobs").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gap_analysis_pending").value(true));
    }

    @Test
    void freeOwner_previouslyEarnedGaps_stillRender_andNotPending() throws Exception {
        // A user who WAS Pro keeps the gap output already produced; the extra
        // null-gap condition from a later free-tier synthesis must not flip
        // the claim into an eternal "re-checking".
        String email = "downgraded-a1@example.com";
        long claimId = claimFor(email);
        saveCondition(claimId, "PTSD", List.of(pipelineGap()));
        saveCondition(claimId, "Tinnitus", null);

        mvc.perform(get("/api/claim/gaps").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gaps", hasSize(1)))
                .andExpect(jsonPath("$.gapAnalysisPending").value(false));
    }
}
