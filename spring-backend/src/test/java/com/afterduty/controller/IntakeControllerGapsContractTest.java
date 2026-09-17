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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0-1 contract test — GET /api/claim/gaps must translate the PIPELINE gap shape
 * (keys exactly as EvidenceGapAnalyzer writes them and GapValidationAgent preserves
 * them: title/description/vasrd_reference/rating_impact/how_to_get_it/triad_leg/
 * target_rating/estimated_time/estimated_cost_usd) into the UI field names. The
 * endpoint historically read label/why/suggest/impact — keys no writer ever
 * produced — so every gap rendered blank. Feeding a pipeline-shaped gap through
 * the real endpoint pins the mapping so key drift fails CI.
 *
 * Also pins the P0-5 envelope signal: an ACTIVE condition with a null gaps field
 * (generation flipped, gap analysis not landed) must flip gapAnalysisPending=true
 * instead of being silently skipped — on this endpoint AND on /api/claim/jobs.
 */
@SpringBootTest
@ActiveProfiles("local")
@Tag("regression")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:intakep0test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.cloud.gcp.sql.enabled=false",
        "spring.autoconfigure.exclude=com.google.cloud.spring.autoconfigure.sql.GcpCloudSqlAutoConfiguration,com.google.cloud.spring.autoconfigure.firestore.GcpFirestoreAutoConfiguration,com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration,com.google.cloud.spring.autoconfigure.secretmanager.GcpSecretManagerAutoConfiguration"
})
class IntakeControllerGapsContractTest {

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
        User u = userRepository.findByEmail(email.toLowerCase())
                .or(() -> userRepository.findByEmail(email))
                .orElseThrow();
        return claimRepository.findByUserIdOrderByCreatedAtDesc(u.getId()).get(0).getId();
    }

    /**
     * A gap map shaped exactly as the live pipeline persists it —
     * EvidenceGapAnalyzer's OUTPUT schema, key for key.
     */
    private Map<String, Object> pipelineGap() {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("type", "nexus_letter");
        g.put("title", "Obtain nexus letter linking PTSD to service");
        g.put("description", "38 CFR 4.130 requires a medical link between the current "
                + "diagnosis and the in-service stressor; no treatment record connects them.");
        g.put("vasrd_reference", "38 CFR 4.130 DC 9411");
        g.put("current_rating", 50);
        g.put("target_rating", 70);
        g.put("triad_leg", "nexus");
        g.put("priority", "high");
        g.put("rating_impact", "Expected increase from 50% to 70%");
        g.put("how_to_get_it", "Ask your treating psychologist for a nexus letter using "
                + "the 'at least as likely as not' language.");
        g.put("estimated_time", "1-2 weeks");
        g.put("estimated_cost_usd", 0);
        return g;
    }

    private IdentifiedCondition saveCondition(long claimId, String name,
                                              List<Map<String, Object>> gaps) {
        return conditionRepository.save(IdentifiedCondition.builder()
                .claimId(claimId)
                .name(name)
                .gaps(gaps)
                .build());
    }

    @Test
    void pipelineShapedGap_mapsToNonEmptyContractFields() throws Exception {
        String email = "gaps-contract@example.com";
        long claimId = claimFor(email);
        saveCondition(claimId, "PTSD", List.of(pipelineGap()));

        mvc.perform(get("/api/claim/gaps").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gaps", hasSize(1)))
                // The four fields that rendered blank pre-fix must be non-empty
                // and carry the pipeline's content.
                .andExpect(jsonPath("$.gaps[0].label", not(emptyString())))
                .andExpect(jsonPath("$.gaps[0].label").value("Obtain nexus letter linking PTSD to service"))
                .andExpect(jsonPath("$.gaps[0].why", not(emptyString())))
                .andExpect(jsonPath("$.gaps[0].why", containsString("requires a medical link")))
                .andExpect(jsonPath("$.gaps[0].why", containsString("(VASRD: 38 CFR 4.130 DC 9411)")))
                .andExpect(jsonPath("$.gaps[0].suggest", not(emptyString())))
                .andExpect(jsonPath("$.gaps[0].suggest", containsString("at least as likely as not")))
                .andExpect(jsonPath("$.gaps[0].impact", not(emptyString())))
                .andExpect(jsonPath("$.gaps[0].impact").value("Expected increase from 50% to 70%"))
                // Humanized type token + pass-through fields.
                .andExpect(jsonPath("$.gaps[0].type").value("Nexus letter"))
                .andExpect(jsonPath("$.gaps[0].condName").value("PTSD"))
                .andExpect(jsonPath("$.gaps[0].priority").value("high"))
                .andExpect(jsonPath("$.gaps[0].status").value("open"))
                .andExpect(jsonPath("$.gaps[0].index").value(0))
                .andExpect(jsonPath("$.gaps[0].triadLeg").value("nexus"))
                .andExpect(jsonPath("$.gaps[0].targetRating").value(70))
                .andExpect(jsonPath("$.gaps[0].estimatedTime").value("1-2 weeks"))
                .andExpect(jsonPath("$.gaps[0].estimatedCostUsd").value(0))
                .andExpect(jsonPath("$.gapAnalysisPending").value(false));
    }

    @Test
    void nullGapCondition_flagsGapAnalysisPending_onGapsAndJobs() throws Exception {
        String email = "gaps-pending@example.com";
        long claimId = claimFor(email);
        saveCondition(claimId, "PTSD", List.of(pipelineGap()));
        // Mid-re-analysis shape: the new generation activated with gaps=null.
        saveCondition(claimId, "Tinnitus", null);

        mvc.perform(get("/api/claim/gaps").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gaps", hasSize(1)))
                .andExpect(jsonPath("$.gapAnalysisPending").value(true));

        mvc.perform(get("/api/claim/jobs").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gap_analysis_pending").value(true))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void supersededCondition_neitherListedNorFlagged() throws Exception {
        String email = "gaps-superseded@example.com";
        long claimId = claimFor(email);
        IdentifiedCondition active = saveCondition(claimId, "PTSD", List.of(pipelineGap()));
        // A superseded prior-generation row with null gaps must NOT flag pending.
        IdentifiedCondition old = saveCondition(claimId, "PTSD (old gen)", null);
        old.setSupersededBy(active.getId());
        conditionRepository.save(old);

        mvc.perform(get("/api/claim/gaps").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gaps", hasSize(1)))
                .andExpect(jsonPath("$.gaps[0].condName").value("PTSD"))
                .andExpect(jsonPath("$.gapAnalysisPending").value(false));
    }

    @Test
    void legacyGapShape_stillReadable_typePassedThrough() throws Exception {
        String email = "gaps-legacy@example.com";
        long claimId = claimFor(email);
        // Legacy Gemini-era shape: {type, description, priority, impact}.
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("type", "In-Service");
        legacy.put("description", "No service treatment record documents the knee injury.");
        legacy.put("impact", "+20%");
        saveCondition(claimId, "Knee strain", List.of(legacy));

        mvc.perform(get("/api/claim/gaps").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gaps[0].type").value("In-Service"))
                .andExpect(jsonPath("$.gaps[0].why", containsString("service treatment record")))
                .andExpect(jsonPath("$.gaps[0].impact").value("+20%"))
                .andExpect(jsonPath("$.gaps[0].priority").value("high"))
                .andExpect(jsonPath("$.gaps[0].status").value("open"));
    }
}
