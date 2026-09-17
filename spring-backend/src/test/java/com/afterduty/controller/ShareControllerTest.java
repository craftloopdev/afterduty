package com.afterduty.controller;

import com.jayway.jsonpath.JsonPath;
import com.afterduty.model.Share;
import com.afterduty.repository.ChatThreadRepository;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.ShareRepository;
import org.hamcrest.Matchers;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("local")
@Tag("regression")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sharetest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.cloud.gcp.sql.enabled=false",
        "spring.autoconfigure.exclude=com.google.cloud.spring.autoconfigure.sql.GcpCloudSqlAutoConfiguration,com.google.cloud.spring.autoconfigure.firestore.GcpFirestoreAutoConfiguration,com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration,com.google.cloud.spring.autoconfigure.secretmanager.GcpSecretManagerAutoConfiguration"
})
class ShareControllerTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    OncePerRequestFilter authFilter;

    @Autowired
    ShareRepository shareRepository;

    @Autowired
    ChatThreadRepository chatThreadRepository;

    @Autowired
    ClaimRepository claimRepository;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(authFilter)
                .build();
    }

    // -------------------------------------------------------------------------
    // T1 — createShare returns 201 with token and acceptUrl
    // -------------------------------------------------------------------------

    @Test
    void createShare_returns201WithTokenAndAcceptUrl() throws Exception {
        String body = mvc.perform(post("/api/shares")
                        .header("X-User-Email", "owner-t1@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerEmail\":\"vso-t1@example.com\",\"canViewAnalysis\":true,\"canUploadDocs\":false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.invitationToken").isNotEmpty())
                .andExpect(jsonPath("$.acceptUrl").value(Matchers.startsWith("https://app.afterduty.app/accept-share/")))
                .andReturn().getResponse().getContentAsString();

        long shareId = JsonPath.parse(body).read("$.id", Long.class);
        assertTrue(shareRepository.findById(shareId).isPresent());
    }

    // -------------------------------------------------------------------------
    // T2 — duplicateShareMergesNotDuplicates
    // -------------------------------------------------------------------------

    @Test
    void duplicateShareMergesNotDuplicates() throws Exception {
        String payload = "{\"viewerEmail\":\"vso-t2@example.com\",\"canViewAnalysis\":false,\"canUploadDocs\":false}";
        String resp1 = mvc.perform(post("/api/shares")
                        .header("X-User-Email", "owner-t2@example.com")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long firstId = JsonPath.parse(resp1).read("$.id", Long.class);
        String firstToken = JsonPath.parse(resp1).read("$.invitationToken");

        // Second POST — same email, updated permissions
        String payload2 = "{\"viewerEmail\":\"vso-t2@example.com\",\"canViewAnalysis\":true,\"canUploadDocs\":true}";
        String resp2 = mvc.perform(post("/api/shares")
                        .header("X-User-Email", "owner-t2@example.com")
                        .contentType(MediaType.APPLICATION_JSON).content(payload2))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long secondId = JsonPath.parse(resp2).read("$.id", Long.class);
        String secondToken = JsonPath.parse(resp2).read("$.invitationToken");

        // P2-2 (Phase G): re-inviting a PENDING share retires the old row and
        // issues a brand-new share with a fresh working token — the old
        // single-use link answers 410 share_revoked instead of lingering dead.
        assertNotEquals(firstId, secondId, "Re-invite must issue a new share row");
        assertNotEquals(firstToken, secondToken, "A fresh token must be issued on the second call");

        // DB: exactly one NON-REVOKED row for this owner+email (old one retired)
        List<Share> shares = shareRepository.findAll().stream()
                .filter(s -> "vso-t2@example.com".equalsIgnoreCase(s.getViewerEmail()))
                .toList();
        assertEquals(2, shares.size(), "Old row retired, new row issued");
        List<Share> live = shares.stream().filter(s -> s.getRevokedAt() == null).toList();
        assertEquals(1, live.size(), "At most one non-revoked share per (claim, email)");
        assertTrue(live.get(0).isCanViewAnalysis(), "Updated permission must be reflected");
    }

    // -------------------------------------------------------------------------
    // T3 — listShares returns own shares
    // -------------------------------------------------------------------------

    @Test
    void listShares_returnsOwnShares() throws Exception {
        // Create a share first
        mvc.perform(post("/api/shares")
                        .header("X-User-Email", "owner-t3@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerEmail\":\"vso-t3@example.com\",\"canViewAnalysis\":true,\"canUploadDocs\":false}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/shares")
                        .header("X-User-Email", "owner-t3@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[?(@.viewerEmail == 'vso-t3@example.com')]").exists());
    }

    // -------------------------------------------------------------------------
    // T4 — patchShare updates flags, token unchanged
    // -------------------------------------------------------------------------

    @Test
    void patchShare_updatesFlagsTokenUnchanged() throws Exception {
        String created = mvc.perform(post("/api/shares")
                        .header("X-User-Email", "owner-t4@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerEmail\":\"vso-t4@example.com\",\"canViewAnalysis\":false,\"canUploadDocs\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long shareId = JsonPath.parse(created).read("$.id", Long.class);
        String originalToken = JsonPath.parse(created).read("$.invitationToken");

        mvc.perform(patch("/api/shares/" + shareId)
                        .header("X-User-Email", "owner-t4@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"canViewAnalysis\":true,\"canUploadDocs\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canViewAnalysis").value(true))
                .andExpect(jsonPath("$.canUploadDocs").value(true))
                .andExpect(jsonPath("$.invitationToken").value(originalToken));

        Share dbShare = shareRepository.findById(shareId).orElseThrow();
        assertTrue(dbShare.isCanViewAnalysis());
        assertTrue(dbShare.isCanUploadDocs());
    }

    // -------------------------------------------------------------------------
    // T5 — patchShare by non-owner returns 403
    // -------------------------------------------------------------------------

    @Test
    void patchShare_byNonOwner_returns403() throws Exception {
        String created = mvc.perform(post("/api/shares")
                        .header("X-User-Email", "owner-t5@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerEmail\":\"vso-t5@example.com\",\"canViewAnalysis\":false,\"canUploadDocs\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long shareId = JsonPath.parse(created).read("$.id", Long.class);

        mvc.perform(patch("/api/shares/" + shareId)
                        .header("X-User-Email", "stranger-t5@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"canViewAnalysis\":true}"))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // T6 — deleteShare sets revokedAt, row preserved, chatThread preserved
    // -------------------------------------------------------------------------

    @Test
    void deleteShare_setsRevokedAt_preservesRowAndChatThread() throws Exception {
        // Setup: owner creates share, viewer accepts (creates ChatThread)
        String created = mvc.perform(post("/api/shares")
                        .header("X-User-Email", "owner-t6@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerEmail\":\"vso-t6@example.com\",\"canViewAnalysis\":true,\"canUploadDocs\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long shareId = JsonPath.parse(created).read("$.id", Long.class);
        String token = JsonPath.parse(created).read("$.invitationToken");

        // Viewer accepts
        mvc.perform(post("/api/shares/accept/" + token)
                        .header("X-User-Email", "vso-t6@example.com"))
                .andExpect(status().isOk());

        // Find viewer's user id and claim id
        Share shareBeforeRevoke = shareRepository.findById(shareId).orElseThrow();
        Long viewerUserId = shareBeforeRevoke.getViewerUserId();
        Long claimId = shareBeforeRevoke.getClaimId();

        // Owner revokes
        mvc.perform(delete("/api/shares/" + shareId)
                        .header("X-User-Email", "owner-t6@example.com"))
                .andExpect(status().is2xxSuccessful());

        // Row still present, revokedAt set
        Share revoked = shareRepository.findById(shareId).orElseThrow();
        assertNotNull(revoked.getRevokedAt(), "revokedAt must be set after DELETE");

        // ChatThread still present
        assertTrue(chatThreadRepository.findByViewerUserIdAndClaimId(viewerUserId, claimId).isPresent(),
                "ChatThread must NOT be deleted on revoke");
    }

    // -------------------------------------------------------------------------
    // T7 — deleteShare by non-owner returns 403
    // -------------------------------------------------------------------------

    @Test
    void deleteShare_byNonOwner_returns403() throws Exception {
        String created = mvc.perform(post("/api/shares")
                        .header("X-User-Email", "owner-t7@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerEmail\":\"vso-t7@example.com\",\"canViewAnalysis\":true,\"canUploadDocs\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long shareId = JsonPath.parse(created).read("$.id", Long.class);

        mvc.perform(delete("/api/shares/" + shareId)
                        .header("X-User-Email", "stranger-t7@example.com"))
                .andExpect(status().isForbidden());

        assertNull(shareRepository.findById(shareId).orElseThrow().getRevokedAt());
    }

    // -------------------------------------------------------------------------
    // T8 — publicPreview returns 200 with preview payload (unauthenticated)
    // -------------------------------------------------------------------------

    @Test
    void publicPreview_noAuth_returns200WithPreviewPayload() throws Exception {
        String created = mvc.perform(post("/api/shares")
                        .header("X-User-Email", "owner-t8@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerEmail\":\"vso-t8@example.com\",\"canViewAnalysis\":true,\"canUploadDocs\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.parse(created).read("$.invitationToken");

        mvc.perform(get("/api/shares/accept/" + token))
                // No X-User-Email header — unauthenticated
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerName").isNotEmpty())
                .andExpect(jsonPath("$.ownerEmail").isNotEmpty())
                .andExpect(jsonPath("$.claimId").isNumber())
                .andExpect(jsonPath("$.canViewAnalysis").isBoolean())
                .andExpect(jsonPath("$.canUploadDocs").isBoolean())
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    // -------------------------------------------------------------------------
    // T9 — publicPreview with invalid token returns 404
    // -------------------------------------------------------------------------

    @Test
    void publicPreview_invalidToken_returns404() throws Exception {
        mvc.perform(get("/api/shares/accept/totally-nonexistent-token-xyz"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // T10 — acceptShare by wrong email returns 403
    // -------------------------------------------------------------------------

    @Test
    void acceptShare_wrongEmail_returns403() throws Exception {
        String created = mvc.perform(post("/api/shares")
                        .header("X-User-Email", "owner-t10@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerEmail\":\"vso-t10@example.com\",\"canViewAnalysis\":true,\"canUploadDocs\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.parse(created).read("$.invitationToken");

        mvc.perform(post("/api/shares/accept/" + token)
                        .header("X-User-Email", "stranger-t10@example.com"))
                .andExpect(status().isForbidden());

        // Token must still be present (share not consumed)
        assertTrue(shareRepository.findByInvitationToken(token).isPresent());
        assertNull(shareRepository.findByInvitationToken(token).get().getAcceptedAt());
    }

    // -------------------------------------------------------------------------
    // T11 — acceptShare by correct email (case-insensitive) returns 200
    // -------------------------------------------------------------------------

    @Test
    void acceptShare_correctEmailCaseInsensitive_returns200AndUpdatesDb() throws Exception {
        String created = mvc.perform(post("/api/shares")
                        .header("X-User-Email", "owner-t11@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerEmail\":\"vso-t11@example.com\",\"canViewAnalysis\":true,\"canUploadDocs\":true}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long shareId = JsonPath.parse(created).read("$.id", Long.class);
        String token = JsonPath.parse(created).read("$.invitationToken");

        // Use uppercase email to verify case-insensitive match
        mvc.perform(post("/api/shares/accept/" + token)
                        .header("X-User-Email", "VSO-T11@EXAMPLE.COM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedAt").exists());

        Share accepted = shareRepository.findById(shareId).orElseThrow();
        assertNotNull(accepted.getAcceptedAt(), "acceptedAt must be set");
        assertNotNull(accepted.getViewerUserId(), "viewerUserId must be set");
        assertNull(accepted.getInvitationToken(), "invitationToken must be cleared (single-use)");

        assertTrue(chatThreadRepository.findByViewerUserIdAndClaimId(
                        accepted.getViewerUserId(), accepted.getClaimId()).isPresent(),
                "ChatThread must be created on accept");
    }

    // -------------------------------------------------------------------------
    // T12 — acceptShare after expiry returns 410
    // -------------------------------------------------------------------------

    @Test
    void acceptShare_afterExpiry_returns410() throws Exception {
        String created = mvc.perform(post("/api/shares")
                        .header("X-User-Email", "owner-t12@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerEmail\":\"vso-t12@example.com\",\"canViewAnalysis\":true,\"canUploadDocs\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long shareId = JsonPath.parse(created).read("$.id", Long.class);
        String token = JsonPath.parse(created).read("$.invitationToken");

        // Backdate the expiry via repository
        Share share = shareRepository.findById(shareId).orElseThrow();
        share.setInvitationExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        shareRepository.save(share);

        mvc.perform(post("/api/shares/accept/" + token)
                        .header("X-User-Email", "vso-t12@example.com"))
                .andExpect(status().isGone()); // 410
    }

    // -------------------------------------------------------------------------
    // T13 — acceptShare with already-accepted (cleared) token returns 404
    // -------------------------------------------------------------------------

    @Test
    void acceptShare_alreadyAcceptedToken_returns404() throws Exception {
        String created = mvc.perform(post("/api/shares")
                        .header("X-User-Email", "owner-t13@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerEmail\":\"vso-t13@example.com\",\"canViewAnalysis\":true,\"canUploadDocs\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.parse(created).read("$.invitationToken");

        // First accept — should succeed
        mvc.perform(post("/api/shares/accept/" + token)
                        .header("X-User-Email", "vso-t13@example.com"))
                .andExpect(status().isOk());

        // Second accept with same token — token was cleared, so findByInvitationToken returns empty → 404
        mvc.perform(post("/api/shares/accept/" + token)
                        .header("X-User-Email", "vso-t13@example.com"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // T14 — profilesList includes own claim and accepted shared claim
    // -------------------------------------------------------------------------

    @Test
    void profilesList_includesOwnAndSharedClaim() throws Exception {
        // Ensure vso-t14 has their own claim by triggering getOrCreateActiveClaim via GET /api/claim
        mvc.perform(get("/api/claim")
                        .header("X-User-Email", "vso-t14@example.com"))
                .andExpect(status().isOk());

        // Owner creates a share for vso-t14
        String created = mvc.perform(post("/api/shares")
                        .header("X-User-Email", "owner-t14@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerEmail\":\"vso-t14@example.com\",\"canViewAnalysis\":true,\"canUploadDocs\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.parse(created).read("$.invitationToken");

        // vso-t14 accepts the share
        mvc.perform(post("/api/shares/accept/" + token)
                        .header("X-User-Email", "vso-t14@example.com"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/shares/profiles")
                        .header("X-User-Email", "vso-t14@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.isOwn == true)]").exists())
                .andExpect(jsonPath("$[?(@.isOwn == false)]").exists())
                .andExpect(jsonPath("$[0].claimId").exists())
                .andExpect(jsonPath("$[1].claimId").exists());
    }
}
