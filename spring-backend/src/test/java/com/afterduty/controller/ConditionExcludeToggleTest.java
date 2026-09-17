package com.afterduty.controller;

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract test — POST /api/claim/conditions/{conditionId}/exclude.
 *
 * <p>"Don't include in my claim" (owner-set, reversible). Pins:
 * <ul>
 *   <li>toggling on/off sets the {@code excludedFromClaim} flag (204);</li>
 *   <li>the conditions LIST still returns the excluded condition (flagged) — it is
 *       moved to the web's "Not filing" section, never dropped;</li>
 *   <li>owner guard: a non-owner (cross-user write) is 403 and the flag is
 *       untouched;</li>
 *   <li>unknown condition → 404; superseded prior-generation row → 404; a
 *       non-boolean body → 400.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("local")
@Tag("regression")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:condexcludetest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.cloud.gcp.sql.enabled=false",
        "spring.autoconfigure.exclude=com.google.cloud.spring.autoconfigure.sql.GcpCloudSqlAutoConfiguration,com.google.cloud.spring.autoconfigure.firestore.GcpFirestoreAutoConfiguration,com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration,com.google.cloud.spring.autoconfigure.secretmanager.GcpSecretManagerAutoConfiguration"
})
class ConditionExcludeToggleTest {

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

    private long claimFor(String email) throws Exception {
        mvc.perform(get("/api/claim").header("X-User-Email", email))
                .andExpect(status().isOk());
        User u = userRepository.findByEmail(email.toLowerCase())
                .or(() -> userRepository.findByEmail(email))
                .orElseThrow();
        return claimRepository.findByUserIdOrderByCreatedAtDesc(u.getId()).get(0).getId();
    }

    private static Map<String, Object> leg(String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("evidence", List.of());
        return m;
    }

    private IdentifiedCondition saveCondition(long claimId, String name, String vasrdCode, int rating) {
        return conditionRepository.save(IdentifiedCondition.builder()
                .claimId(claimId)
                .name(name)
                .vasrdCode(vasrdCode)
                .estimatedRating(rating)
                .triadDiagnosis(leg("STRONG"))
                .triadInService(leg("STRONG"))
                .triadNexus(leg("STRONG"))
                .build());
    }

    @Test
    void toggleOnThenOff_setsFlag_andListStillReturnsItFlagged() throws Exception {
        String email = "cond-exclude@example.com";
        long claimId = claimFor(email);
        IdentifiedCondition cond = saveCondition(claimId, "PTSD", "9411", 70);

        // Exclude.
        mvc.perform(post("/api/claim/conditions/" + cond.getId() + "/exclude")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"excluded\":true}"))
                .andExpect(status().isNoContent());
        assertTrue(conditionRepository.findById(cond.getId()).orElseThrow().getExcludedFromClaim());

        // The conditions LIST still returns it, flagged excludedFromClaim=true.
        mvc.perform(get("/api/claim/conditions").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("PTSD"))
                .andExpect(jsonPath("$[0].excludedFromClaim").value(true));

        // Include again (reversible).
        mvc.perform(post("/api/claim/conditions/" + cond.getId() + "/exclude")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"excluded\":false}"))
                .andExpect(status().isNoContent());
        assertFalse(conditionRepository.findById(cond.getId()).orElseThrow().getExcludedFromClaim());

        mvc.perform(get("/api/claim/conditions").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].excludedFromClaim").value(false));
    }

    @Test
    void nonOwner_is403_andFlagUntouched() throws Exception {
        String owner = "cond-exclude-owner@example.com";
        String attacker = "cond-exclude-attacker@example.com";
        long ownerClaimId = claimFor(owner);
        IdentifiedCondition cond = saveCondition(ownerClaimId, "PTSD", "9411", 70);
        claimFor(attacker);

        mvc.perform(post("/api/claim/conditions/" + cond.getId() + "/exclude")
                        .header("X-User-Email", attacker)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"excluded\":true}"))
                .andExpect(status().isForbidden());

        // The owner's condition is unchanged (never excluded by the attacker).
        assertFalse(Boolean.TRUE.equals(
                conditionRepository.findById(cond.getId()).orElseThrow().getExcludedFromClaim()));
    }

    @Test
    void unknownCondition_is404() throws Exception {
        String email = "cond-exclude-missing@example.com";
        claimFor(email);
        mvc.perform(post("/api/claim/conditions/999999/exclude")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"excluded\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void supersededCondition_is404() throws Exception {
        String email = "cond-exclude-superseded@example.com";
        long claimId = claimFor(email);
        IdentifiedCondition active = saveCondition(claimId, "PTSD", "9411", 50);
        IdentifiedCondition old = saveCondition(claimId, "PTSD (old gen)", "9411", 70);
        old.setSupersededBy(active.getId());
        conditionRepository.save(old);

        mvc.perform(post("/api/claim/conditions/" + old.getId() + "/exclude")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"excluded\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonBooleanBody_is400() throws Exception {
        String email = "cond-exclude-badbody@example.com";
        long claimId = claimFor(email);
        IdentifiedCondition cond = saveCondition(claimId, "PTSD", "9411", 70);

        mvc.perform(post("/api/claim/conditions/" + cond.getId() + "/exclude")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"excluded\":\"yes\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/claim/conditions/" + cond.getId() + "/exclude")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unauthenticated_is401() throws Exception {
        String email = "cond-exclude-unauth@example.com";
        long claimId = claimFor(email);
        IdentifiedCondition cond = saveCondition(claimId, "PTSD", "9411", 70);

        mvc.perform(post("/api/claim/conditions/" + cond.getId() + "/exclude")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"excluded\":true}"))
                .andExpect(status().isUnauthorized());
    }
}
