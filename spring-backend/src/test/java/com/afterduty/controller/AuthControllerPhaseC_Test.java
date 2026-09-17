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

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("local")
@Tag("regression")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:getmetest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.cloud.gcp.sql.enabled=false",
        "spring.autoconfigure.exclude=com.google.cloud.spring.autoconfigure.sql.GcpCloudSqlAutoConfiguration,com.google.cloud.spring.autoconfigure.firestore.GcpFirestoreAutoConfiguration,com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration,com.google.cloud.spring.autoconfigure.secretmanager.GcpSecretManagerAutoConfiguration"
})
class AuthControllerPhaseC_Test {

    @Autowired
    WebApplicationContext context;

    @Autowired
    OncePerRequestFilter authFilter;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(authFilter)
                .build();
    }

    // -------------------------------------------------------------------------
    // T18 — getMe: fresh user with no shares, sharedProfiles is empty array (not null)
    // -------------------------------------------------------------------------

    @Test
    void getMe_noShares_sharedProfilesIsEmptyArray() throws Exception {
        mvc.perform(get("/api/auth/me")
                        .header("X-User-Email", "fresh-t18@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.sharedProfiles").isArray())
                .andExpect(jsonPath("$.sharedProfiles.length()").value(0));
    }

    // -------------------------------------------------------------------------
    // T19 — getMe: user with one accepted share, sharedProfiles has 1 entry with isOwn=false
    // -------------------------------------------------------------------------

    @Test
    void getMe_oneAcceptedShare_sharedProfilesHasOneEntry() throws Exception {
        // Setup: owner creates share; viewer accepts via POST /api/shares/accept/{token}
        String created = mvc.perform(post("/api/shares")
                        .header("X-User-Email", "owner-t19@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"viewerEmail\":\"viewer-t19@example.com\",\"canViewAnalysis\":true,\"canUploadDocs\":false}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.parse(created).read("$.invitationToken");

        mvc.perform(post("/api/shares/accept/" + token)
                        .header("X-User-Email", "viewer-t19@example.com"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/auth/me")
                        .header("X-User-Email", "viewer-t19@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sharedProfiles").isArray())
                .andExpect(jsonPath("$.sharedProfiles.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.sharedProfiles[?(@.isOwn == false)]").exists())
                .andExpect(jsonPath("$.sharedProfiles[?(@.isOwn == false)].claimId").exists());
    }
}
