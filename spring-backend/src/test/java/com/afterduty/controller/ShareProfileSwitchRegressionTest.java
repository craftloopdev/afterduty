package com.afterduty.controller;

import com.jayway.jsonpath.JsonPath;
import com.afterduty.model.Share;
import com.afterduty.model.User;
import com.afterduty.repository.ClaimRepository;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Regression tests for the end-to-end share → profile-switch flow.
 *
 * <p>Each test drives the full HTTP stack via MockMvc:
 * <ol>
 *   <li>Owner calls POST /api/shares — invitation token issued.</li>
 *   <li>Viewer calls POST /api/shares/accept/{token} — share accepted, token cleared.</li>
 *   <li>Viewer accesses owner's claim data via X-View-As header, scope rules enforced.</li>
 * </ol>
 *
 * <p>Tests are isolated by using distinct email addresses (suffixed with the test id).
 * All tests share one H2 in-memory database named {@code shareprofileswitch}.
 */
@SpringBootTest
@ActiveProfiles("local")
@Tag("regression")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:shareprofileswitch;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.cloud.gcp.sql.enabled=false",
        "spring.autoconfigure.exclude=com.google.cloud.spring.autoconfigure.sql.GcpCloudSqlAutoConfiguration,com.google.cloud.spring.autoconfigure.firestore.GcpFirestoreAutoConfiguration,com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration,com.google.cloud.spring.autoconfigure.secretmanager.GcpSecretManagerAutoConfiguration"
})
class ShareProfileSwitchRegressionTest {

    private static final Instant FUTURE = Instant.now().plus(365, ChronoUnit.DAYS);

    @Autowired
    WebApplicationContext context;

    @Autowired
    OncePerRequestFilter authFilter;

    @Autowired
    ClaimRepository claimRepository;

    @Autowired
    ShareRepository shareRepository;

    @Autowired
    UserRepository userRepository;

    MockMvc mvc;

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
     * Full HTTP flow: owner creates share via POST /api/shares, viewer accepts
     * via POST /api/shares/accept/{token}. If {@code ownerHasPro} is true, the
     * owner's subscriptionExpiresAt is set to a future timestamp before the share
     * is created (so VIEW_ANALYSIS scope rules are satisfied).
     *
     * @return the owner's claim id
     */
    private long setupAcceptedShare(String ownerEmail, String viewerEmail,
                                    boolean canViewAnalysis, boolean canUploadDocs,
                                    boolean ownerHasPro) throws Exception {
        // Auto-create owner user + claim via the dev-mode auth filter
        mvc.perform(get("/api/claim").header("X-User-Email", ownerEmail))
                .andExpect(status().isOk());

        // Grant Pro to owner if requested
        if (ownerHasPro) {
            User owner = userRepository.findByEmail(ownerEmail.toLowerCase())
                    .or(() -> userRepository.findByEmail(ownerEmail))
                    .orElseThrow(() -> new IllegalStateException("Owner not found: " + ownerEmail));
            owner.setSubscriptionExpiresAt(FUTURE);
            userRepository.save(owner);
        }

        // Owner creates the share invitation
        String shareBody = String.format(
                "{\"viewerEmail\":\"%s\",\"canViewAnalysis\":%b,\"canUploadDocs\":%b}",
                viewerEmail, canViewAnalysis, canUploadDocs);
        String shareResponse = mvc.perform(post("/api/shares")
                        .header("X-User-Email", ownerEmail)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(shareBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String token = JsonPath.parse(shareResponse).read("$.invitationToken");
        long ownerClaimId = ((Number) JsonPath.parse(shareResponse).read("$.claimId")).longValue();

        // Auto-create viewer user, then accept the share
        mvc.perform(get("/api/claim").header("X-User-Email", viewerEmail));

        mvc.perform(post("/api/shares/accept/" + token)
                        .header("X-User-Email", viewerEmail))
                .andExpect(status().isOk());

        return ownerClaimId;
    }

    // -------------------------------------------------------------------------
    // T-SP1 — viewer can GET /api/claim/evidence with X-View-As after token accept
    // -------------------------------------------------------------------------

    @Test
    void shareAccept_viewerCanGetEvidenceViaXViewAs() throws Exception {
        long ownerClaimId = setupAcceptedShare(
                "owner-sp1@example.com", "viewer-sp1@example.com",
                /*canViewAnalysis=*/true, /*canUploadDocs=*/false, /*ownerHasPro=*/false);

        mvc.perform(get("/api/claim/evidence")
                        .header("X-User-Email", "viewer-sp1@example.com")
                        .header("X-View-As", String.valueOf(ownerClaimId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // -------------------------------------------------------------------------
    // T-SP2 — viewer can GET /api/claim/conditions when canViewAnalysis=true + owner Pro
    // -------------------------------------------------------------------------

    @Test
    void shareAccept_viewerCanViewAnalysis_whenOwnerPro() throws Exception {
        long ownerClaimId = setupAcceptedShare(
                "owner-sp2@example.com", "viewer-sp2@example.com",
                /*canViewAnalysis=*/true, /*canUploadDocs=*/false, /*ownerHasPro=*/true);

        mvc.perform(get("/api/claim/conditions")
                        .header("X-User-Email", "viewer-sp2@example.com")
                        .header("X-View-As", String.valueOf(ownerClaimId)))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // T-SP3 — viewer cannot GET /api/claim/conditions when canViewAnalysis=false
    // -------------------------------------------------------------------------

    @Test
    void shareAccept_viewerCannotViewAnalysis_whenFlagFalse() throws Exception {
        long ownerClaimId = setupAcceptedShare(
                "owner-sp3@example.com", "viewer-sp3@example.com",
                /*canViewAnalysis=*/false, /*canUploadDocs=*/false, /*ownerHasPro=*/true);

        mvc.perform(get("/api/claim/conditions")
                        .header("X-User-Email", "viewer-sp3@example.com")
                        .header("X-View-As", String.valueOf(ownerClaimId)))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // T-SP4 — revoke blocks subsequent viewer access
    // -------------------------------------------------------------------------

    @Test
    void shareAccept_revokeBlocks_viewerAccess() throws Exception {
        long ownerClaimId = setupAcceptedShare(
                "owner-sp4@example.com", "viewer-sp4@example.com",
                /*canViewAnalysis=*/true, /*canUploadDocs=*/false, /*ownerHasPro=*/false);

        // Confirm access before revoke
        mvc.perform(get("/api/claim/evidence")
                        .header("X-User-Email", "viewer-sp4@example.com")
                        .header("X-View-As", String.valueOf(ownerClaimId)))
                .andExpect(status().isOk());

        // Look up the share row and revoke it
        Share share = shareRepository.findAll().stream()
                .filter(s -> s.getClaimId().equals(ownerClaimId) && s.getRevokedAt() == null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Expected active share for claim " + ownerClaimId));
        mvc.perform(delete("/api/shares/" + share.getId())
                        .header("X-User-Email", "owner-sp4@example.com"))
                .andExpect(status().is2xxSuccessful());

        // Confirm blocked after revoke
        mvc.perform(get("/api/claim/evidence")
                        .header("X-User-Email", "viewer-sp4@example.com")
                        .header("X-View-As", String.valueOf(ownerClaimId)))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // T-SP5 — GET /api/shares/profiles after accept contains isOwn=false entry
    // -------------------------------------------------------------------------

    @Test
    void profilesList_afterAccept_containsOwnerEntry() throws Exception {
        long ownerClaimId = setupAcceptedShare(
                "owner-sp5@example.com", "viewer-sp5@example.com",
                /*canViewAnalysis=*/true, /*canUploadDocs=*/false, /*ownerHasPro=*/false);

        mvc.perform(get("/api/shares/profiles")
                        .header("X-User-Email", "viewer-sp5@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.isOwn == false)]").exists())
                .andExpect(jsonPath("$[?(@.claimId == " + ownerClaimId + ")]").exists());
    }

    // -------------------------------------------------------------------------
    // T-SP6 — malformed X-View-As header returns 400
    // -------------------------------------------------------------------------

    @Test
    void xViewAs_malformedHeader_returns400() throws Exception {
        // Ensure the viewer user + claim exist
        mvc.perform(get("/api/claim").header("X-User-Email", "viewer-sp6@example.com"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/claim/evidence")
                        .header("X-User-Email", "viewer-sp6@example.com")
                        .header("X-View-As", "notanumber"))
                .andExpect(status().isBadRequest()); // 400
    }
}
