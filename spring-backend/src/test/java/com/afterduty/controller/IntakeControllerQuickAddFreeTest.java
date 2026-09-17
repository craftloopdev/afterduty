package com.afterduty.controller;

import com.afterduty.model.EvidenceItem;
import com.afterduty.model.User;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.EvidenceRepository;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0-7 interim — quick-add TEXT statements are free, exactly like file uploads
 * (same pipeline, zero cost difference; paid analysis stays gated in
 * AnalysisScheduler). The endpoint returned 402 for free users while
 * POST /evidence was explicitly free — an incoherent gate, now removed.
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
class IntakeControllerQuickAddFreeTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    OncePerRequestFilter authFilter;

    @Autowired
    ClaimRepository claimRepository;

    @Autowired
    EvidenceRepository evidenceRepository;

    @Autowired
    UserRepository userRepository;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(authFilter)
                .build();
    }

    /** Auto-creates the user (NO subscription — free tier) and returns the claim id. */
    private long freeUserClaim(String email) throws Exception {
        mvc.perform(get("/api/claim").header("X-User-Email", email))
                .andExpect(status().isOk());
        User u = userRepository.findByEmail(email.toLowerCase())
                .or(() -> userRepository.findByEmail(email))
                .orElseThrow();
        assertThat(u.hasActiveSubscription()).as("test premise: user is free tier").isFalse();
        return claimRepository.findByUserIdOrderByCreatedAtDesc(u.getId()).get(0).getId();
    }

    @Test
    void freeUser_quickAddTextStatement_returns201_andPersistsEvidence() throws Exception {
        String email = "quickadd-free@example.com";
        long claimId = freeUserClaim(email);

        mvc.perform(post("/api/claim/quick-add")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Ringing in both ears since the 2019 deployment.\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("quick_add"));

        List<EvidenceItem> evidence = evidenceRepository.findByClaimIdOrderByCreatedAt(claimId);
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).getSourceType()).isEqualTo("quick_add");
        assertThat(evidence.get(0).getRawContent()).contains("Ringing in both ears");
    }

    @Test
    void quickAdd_blankText_returns400() throws Exception {
        String email = "quickadd-blank@example.com";
        freeUserClaim(email);

        mvc.perform(post("/api/claim/quick-add")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void quickAdd_oversizedText_returns400_noRow() throws Exception {
        String email = "quickadd-huge@example.com";
        long claimId = freeUserClaim(email);

        String huge = "x".repeat(10_001);
        mvc.perform(post("/api/claim/quick-add")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + huge + "\"}"))
                .andExpect(status().isBadRequest());

        assertThat(evidenceRepository.findByClaimIdOrderByCreatedAt(claimId)).isEmpty();
    }
}
