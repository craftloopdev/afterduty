package com.afterduty.controller;

import com.afterduty.model.Claim;
import com.afterduty.model.Share;
import com.afterduty.model.User;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.EvidenceRepository;
import com.afterduty.repository.ShareRepository;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("local")
@Tag("regression")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:phasectest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.cloud.gcp.sql.enabled=false",
        "spring.autoconfigure.exclude=com.google.cloud.spring.autoconfigure.sql.GcpCloudSqlAutoConfiguration,com.google.cloud.spring.autoconfigure.firestore.GcpFirestoreAutoConfiguration,com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration,com.google.cloud.spring.autoconfigure.secretmanager.GcpSecretManagerAutoConfiguration"
})
// In-memory GCS: the T11/T12 upload-permission probes ride the multipart lane
// (the live one), whose success path writes the file bytes to storage.
@org.springframework.context.annotation.Import(com.afterduty.service.FakeGcsStorageTestConfig.class)
class IntakeControllerPhaseC_Test {

    private static final Instant FUTURE = Instant.now().plus(365, ChronoUnit.DAYS);

    @Autowired
    WebApplicationContext context;

    @Autowired
    OncePerRequestFilter authFilter;

    @Autowired
    ClaimRepository claimRepository;

    @Autowired
    EvidenceRepository evidenceRepository;

    @Autowired
    ShareRepository shareRepository;

    @Autowired
    UserRepository userRepository;

    MockMvc mvc;

    // Track VSO user id for T12
    Long lastVsoUserId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(authFilter)
                .build();
    }

    // -------------------------------------------------------------------------
    // Setup helpers
    // -------------------------------------------------------------------------

    /**
     * Creates an owner with Pro subscription, a VSO user, and an accepted share.
     * The owner's claim is auto-created via GET /api/claim.
     * Returns the owner's claim id.
     */
    private long setupOwnerWithProAndShare(String ownerEmail, String vsoEmail,
                                           boolean canViewAnalysis, boolean canUploadDocs) throws Exception {
        // Trigger claim creation for owner
        mvc.perform(get("/api/claim").header("X-User-Email", ownerEmail))
                .andExpect(status().isOk());

        // Grant owner Pro subscription
        User owner = userOf(ownerEmail);
        owner.setSubscriptionExpiresAt(FUTURE);
        userRepository.save(owner);

        // Find owner's claim
        long ownerClaimId = claimRepository.findByUserIdOrderByCreatedAtDesc(owner.getId())
                .get(0).getId();

        // Create share via repo (bypasses share expiry logic; simulates accepted share)
        User vso = userOf(vsoEmail);
        lastVsoUserId = vso.getId();

        Share share = new Share();
        share.setOwnerUserId(owner.getId());
        share.setClaimId(ownerClaimId);
        share.setViewerUserId(vso.getId());
        share.setViewerEmail(vsoEmail.toLowerCase());
        share.setCanViewAnalysis(canViewAnalysis);
        share.setCanUploadDocs(canUploadDocs);
        share.setAcceptedAt(Instant.now().minusSeconds(60));
        share.setRevokedAt(null);
        shareRepository.save(share);

        return ownerClaimId;
    }

    /**
     * Creates an owner WITHOUT Pro subscription, a VSO user, and an accepted share.
     * Returns the owner's claim id.
     */
    private long setupOwnerWithoutProAndShare(String ownerEmail, String vsoEmail,
                                              boolean canViewAnalysis, boolean canUploadDocs) throws Exception {
        // Trigger claim creation for owner (owner has no Pro — default)
        mvc.perform(get("/api/claim").header("X-User-Email", ownerEmail))
                .andExpect(status().isOk());

        User owner = userOf(ownerEmail);
        long ownerClaimId = claimRepository.findByUserIdOrderByCreatedAtDesc(owner.getId())
                .get(0).getId();

        User vso = userOf(vsoEmail);
        lastVsoUserId = vso.getId();

        Share share = new Share();
        share.setOwnerUserId(owner.getId());
        share.setClaimId(ownerClaimId);
        share.setViewerUserId(vso.getId());
        share.setViewerEmail(vsoEmail.toLowerCase());
        share.setCanViewAnalysis(canViewAnalysis);
        share.setCanUploadDocs(canUploadDocs);
        share.setAcceptedAt(Instant.now().minusSeconds(60));
        share.setRevokedAt(null);
        shareRepository.save(share);

        return ownerClaimId;
    }

    /**
     * Owner Pro + VSO Pro + accepted share.
     */
    private long setupOwnerWithProViewerWithProAndShare(String ownerEmail, String vsoEmail,
                                                        boolean canViewAnalysis, boolean canUploadDocs) throws Exception {
        long ownerClaimId = setupOwnerWithProAndShare(ownerEmail, vsoEmail, canViewAnalysis, canUploadDocs);

        // Grant VSO Pro as well
        User vso = userOf(vsoEmail);
        vso.setSubscriptionExpiresAt(FUTURE);
        userRepository.save(vso);

        return ownerClaimId;
    }

    /**
     * Resolves (or auto-creates) the User entity by email (via the dev-mode filter path).
     * After a GET /api/claim with that email the user is guaranteed to exist.
     */
    private User userOf(String email) throws Exception {
        // Ensure the user exists by touching the API
        mvc.perform(get("/api/claim").header("X-User-Email", email));
        return userRepository.findByEmail(email.toLowerCase())
                .or(() -> userRepository.findByEmail(email))
                .orElseThrow(() -> new IllegalStateException("User not found: " + email));
    }

    // -------------------------------------------------------------------------
    // T8 — VSO with can_view_analysis=true, owner Pro GET /api/claim/evidence returns 200
    // -------------------------------------------------------------------------

    @Test
    void vso_viewDocs_ownerPro_getEvidence_returns200() throws Exception {
        long ownerClaimId = setupOwnerWithProAndShare(
                "owner-t8@example.com", "vso-t8@example.com",
                /*canViewAnalysis=*/true, /*canUploadDocs=*/false);

        mvc.perform(get("/api/claim/evidence")
                        .header("X-User-Email", "vso-t8@example.com")
                        .header("X-View-As", String.valueOf(ownerClaimId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // -------------------------------------------------------------------------
    // T9 — VSO with can_view_analysis=false GET /api/claim/conditions returns 403
    // -------------------------------------------------------------------------

    @Test
    void vso_canViewAnalysisFalse_getConditions_returns403() throws Exception {
        long ownerClaimId = setupOwnerWithProAndShare(
                "owner-t9@example.com", "vso-t9@example.com",
                /*canViewAnalysis=*/false, /*canUploadDocs=*/false);

        mvc.perform(get("/api/claim/conditions")
                        .header("X-User-Email", "vso-t9@example.com")
                        .header("X-View-As", String.valueOf(ownerClaimId)))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // T10 — Owner Pro lapsed, VIEW_ANALYSIS denied for VSO with can_view_analysis=true
    // -------------------------------------------------------------------------

    @Test
    void vso_canViewAnalysisTrue_ownerNoProLapsed_getConditions_returns403() throws Exception {
        long ownerClaimId = setupOwnerWithoutProAndShare(
                "owner-t10@example.com", "vso-t10@example.com",
                /*canViewAnalysis=*/true, /*canUploadDocs=*/false);

        mvc.perform(get("/api/claim/conditions")
                        .header("X-User-Email", "vso-t10@example.com")
                        .header("X-View-As", String.valueOf(ownerClaimId)))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // T11 — VSO with can_upload_docs=false POST /api/claim/evidence returns 403
    // -------------------------------------------------------------------------

    @Test
    void vso_canUploadDocsFalse_postEvidence_returns403_noNewRow() throws Exception {
        long ownerClaimId = setupOwnerWithProAndShare(
                "owner-t11@example.com", "vso-t11@example.com",
                /*canViewAnalysis=*/false, /*canUploadDocs=*/false);
        int countBefore = evidenceRepository.findByClaimIdOrderByCreatedAt(ownerClaimId).size();

        // Multipart is the live upload lane (the JSON overload left with the
        // Flutter frontend, 2026-08-02) — the UPLOAD_DOCS gate must hold on it.
        mvc.perform(multipart("/api/claim/evidence")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "note.txt", MediaType.TEXT_PLAIN_VALUE, "test note".getBytes()))
                        .header("X-User-Email", "vso-t11@example.com")
                        .header("X-View-As", String.valueOf(ownerClaimId)))
                .andExpect(status().isForbidden());

        assertThat(evidenceRepository.findByClaimIdOrderByCreatedAt(ownerClaimId).size())
                .isEqualTo(countBefore);
    }

    // -------------------------------------------------------------------------
    // T12 — VSO with can_upload_docs=true POST /api/claim/evidence returns 2xx
    // -------------------------------------------------------------------------

    @Test
    void vso_canUploadDocsTrue_postEvidence_returns2xx_rowOnOwnerClaim() throws Exception {
        long ownerClaimId = setupOwnerWithProAndShare(
                "owner-t12@example.com", "vso-t12@example.com",
                /*canViewAnalysis=*/false, /*canUploadDocs=*/true);

        mvc.perform(multipart("/api/claim/evidence")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "vso-note.txt", MediaType.TEXT_PLAIN_VALUE,
                                "VSO-uploaded note".getBytes()))
                        .header("X-User-Email", "vso-t12@example.com")
                        .header("X-View-As", String.valueOf(ownerClaimId)))
                .andExpect(status().is2xxSuccessful());

        List<?> ownerEvidence = evidenceRepository.findByClaimIdOrderByCreatedAt(ownerClaimId);
        assertThat(ownerEvidence).isNotEmpty();

        // Confirm NOT on VSO's own claim
        // lastVsoUserId was set by setupOwnerWithProAndShare
        List<Claim> vsoClaims = claimRepository.findByUserIdOrderByCreatedAtDesc(lastVsoUserId);
        if (!vsoClaims.isEmpty()) {
            long vsoClaimId = vsoClaims.get(0).getId();
            assertThat(evidenceRepository.findByClaimIdOrderByCreatedAt(vsoClaimId)).isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // T13 — VSO with can_view_analysis=true but no viewer Pro, POST /api/claim/chat returns 403
    // -------------------------------------------------------------------------

    @Test
    void vso_canViewAnalysisTrue_noViewerPro_chat_returns403() throws Exception {
        // Owner has Pro; VSO has no Pro; share has canViewAnalysis=true
        long ownerClaimId = setupOwnerWithProAndShare(
                "owner-t13@example.com", "vso-t13@example.com",
                /*canViewAnalysis=*/true, /*canUploadDocs=*/false);

        mvc.perform(post("/api/claim/chat")
                        .header("X-User-Email", "vso-t13@example.com")
                        .header("X-View-As", String.valueOf(ownerClaimId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // T14 — VSO with can_view_analysis=true + viewer Pro POST /api/claim/chat passes scope gate
    // -------------------------------------------------------------------------

    @Test
    void vso_canViewAnalysisTrue_viewerPro_chat_passesScopeGate() throws Exception {
        // Owner Pro + VSO Pro + canViewAnalysis=true
        long ownerClaimId = setupOwnerWithProViewerWithProAndShare(
                "owner-t14@example.com", "vso-t14@example.com",
                /*canViewAnalysis=*/true, /*canUploadDocs=*/false);

        // The scope gate is what this test asserts. The downstream chat agent
        // throws IllegalStateException in the test environment because the
        // Claude API key isn't set — that's expected and irrelevant to the
        // scope check. Capture the response if scope passes; capture the
        // exception cause if scope fails or the agent blew up — both paths
        // are non-403, and that's the only thing we care about here.
        int status;
        try {
            status = mvc.perform(post("/api/claim/chat")
                            .header("X-User-Email", "vso-t14@example.com")
                            .header("X-View-As", String.valueOf(ownerClaimId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"hello\"}"))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        } catch (jakarta.servlet.ServletException e) {
            // ChatAgent throwing IllegalStateException for missing Claude
            // key surfaces as a ServletException here. That's NOT a scope
            // denial — it's a downstream config gap in the test env.
            assertThat(e.getCause()).isInstanceOfAny(
                    IllegalStateException.class,
                    org.springframework.web.server.ResponseStatusException.class);
            // If it WAS a 403-shaped ResponseStatusException we'd have failed
            // at the scope gate, so check that explicitly:
            if (e.getCause() instanceof org.springframework.web.server.ResponseStatusException rse) {
                assertThat(rse.getStatusCode().value()).isNotEqualTo(403);
            }
            return; // exception path is acceptable; scope gate clearly passed
        }
        assertThat(status).isNotEqualTo(403);
    }

    // -------------------------------------------------------------------------
    // T15 — X-View-As pointing at a claim with no access returns 403 (not 404)
    // -------------------------------------------------------------------------

    @Test
    void noAccessForClaim_returns403NotLeak404() throws Exception {
        // Owner creates a claim but grants NO share to the VSO
        String ownerEmail = "owner-t15@example.com";
        mvc.perform(get("/api/claim").header("X-User-Email", ownerEmail))
                .andExpect(status().isOk());

        User owner = userOf(ownerEmail);
        long ownerClaimId = claimRepository.findByUserIdOrderByCreatedAtDesc(owner.getId())
                .get(0).getId();

        mvc.perform(get("/api/claim/evidence")
                        .header("X-User-Email", "vso-t15@example.com")
                        .header("X-View-As", String.valueOf(ownerClaimId)))
                .andExpect(status().isForbidden()); // 403
    }

    // -------------------------------------------------------------------------
    // T16 — X-View-As set to own claim id behaves identically to no header
    // -------------------------------------------------------------------------

    @Test
    void ownClaimId_inHeader_equivalentToNoHeader() throws Exception {
        String email = "veteran-t16@example.com";
        mvc.perform(get("/api/claim").header("X-User-Email", email))
                .andExpect(status().isOk());

        User veteran = userOf(email);
        long ownClaimId = claimRepository.findByUserIdOrderByCreatedAtDesc(veteran.getId())
                .get(0).getId();

        // Without header
        mvc.perform(get("/api/claim/evidence").header("X-User-Email", email))
                .andExpect(status().isOk());

        // With X-View-As pointing at own claim
        mvc.perform(get("/api/claim/evidence")
                        .header("X-User-Email", email)
                        .header("X-View-As", String.valueOf(ownClaimId)))
                .andExpect(status().isOk());
    }
}
