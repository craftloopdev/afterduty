package com.afterduty.controller;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reviewer-demo allowlist end to end (capacitor-ios-spec §H.7.2): with
 * {@code SUBSCRIPTION_DEMO_EMAILS} staffing an email, {@code GET
 * /api/subscription/status} must report {@code active:true} for that account —
 * with NO subscription row — so the App-Review reviewer sees Pro features. A
 * non-allowlisted account still reports {@code active:false}. This is the gate
 * that, if broken, hands the reviewer a locked app (a §H.1-class rejection).
 */
@SpringBootTest
@ActiveProfiles("local")
@Tag("regression")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:subdemotest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.cloud.gcp.sql.enabled=false",
        // The one property under test — the reviewer-demo allowlist.
        "va-claim.subscription.demo-emails=reviewer@apple-demo.com",
        "spring.autoconfigure.exclude=com.google.cloud.spring.autoconfigure.sql.GcpCloudSqlAutoConfiguration,com.google.cloud.spring.autoconfigure.firestore.GcpFirestoreAutoConfiguration,com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration,com.google.cloud.spring.autoconfigure.secretmanager.GcpSecretManagerAutoConfiguration"
})
class SubscriptionDemoEmailTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    OncePerRequestFilter authFilter;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(authFilter).build();
    }

    /** Ensure the dev X-User-Email account exists (auto-created by the claim endpoint). */
    private void ensureUser(String email) throws Exception {
        mvc.perform(get("/api/claim").header("X-User-Email", email)).andExpect(status().isOk());
    }

    @Test
    void demoAllowlistedEmail_reportsActivePro_withNoSubscription() throws Exception {
        String email = "reviewer@apple-demo.com";
        ensureUser(email);
        mvc.perform(get("/api/subscription/status").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                // Computed entitlement only — no real subscription row, no source.
                .andExpect(jsonPath("$.expires_at").doesNotExist());
    }

    @Test
    void nonAllowlistedEmail_reportsInactive() throws Exception {
        String email = "regular-vet@example.com";
        ensureUser(email);
        mvc.perform(get("/api/subscription/status").header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }
}
