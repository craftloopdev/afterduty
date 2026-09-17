package com.afterduty.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.cloud.gcp.sql.enabled=false",
        "spring.autoconfigure.exclude=com.google.cloud.spring.autoconfigure.sql.GcpCloudSqlAutoConfiguration,com.google.cloud.spring.autoconfigure.firestore.GcpFirestoreAutoConfiguration,com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration,com.google.cloud.spring.autoconfigure.secretmanager.GcpSecretManagerAutoConfiguration"
})
class UsageControllerIT {

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

    @Test
    void returns401WhenUnauthenticated() throws Exception {
        mvc.perform(get("/api/usage"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsUsageForDevModeUser() throws Exception {
        mvc.perform(get("/api/usage").header("X-User-Email", "test-usage@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.percentUsed").exists())
                .andExpect(jsonPath("$.atLimit").exists())
                .andExpect(jsonPath("$.periodStart").exists())
                .andExpect(jsonPath("$.periodEnd").exists());
    }

    @Test
    void breakdown_returns401WhenUnauthenticated() throws Exception {
        mvc.perform(get("/api/usage/breakdown"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void breakdown_returnsContractShapeForDevModeUser() throws Exception {
        // No ledger rows for a brand-new dev user ⇒ empty breakdown, but the
        // contract fields (period, reset, limit/spent/remaining, flags, byFeature)
        // are all present and typed.
        mvc.perform(get("/api/usage/breakdown").header("X-User-Email", "test-breakdown@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodStart").exists())
                .andExpect(jsonPath("$.periodEnd").exists())
                .andExpect(jsonPath("$.resetAt").exists())
                .andExpect(jsonPath("$.limitCents").isNumber())
                .andExpect(jsonPath("$.spentCents").value(0))
                .andExpect(jsonPath("$.remainingCents").isNumber())
                .andExpect(jsonPath("$.atLimit").value(false))
                .andExpect(jsonPath("$.unlimited").isBoolean())
                .andExpect(jsonPath("$.byFeature").isArray())
                .andExpect(jsonPath("$.byFeature").isEmpty());
    }
}
