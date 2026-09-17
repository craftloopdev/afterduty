package com.afterduty.controller;

import com.afterduty.model.User;
import com.afterduty.repository.UserRepository;
import com.afterduty.service.StripeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("local")
@Tag("regression")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:tiertest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.cloud.gcp.sql.enabled=false",
        "spring.autoconfigure.exclude=com.google.cloud.spring.autoconfigure.sql.GcpCloudSqlAutoConfiguration,com.google.cloud.spring.autoconfigure.firestore.GcpFirestoreAutoConfiguration,com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration,com.google.cloud.spring.autoconfigure.secretmanager.GcpSecretManagerAutoConfiguration"
})
class SubscriptionStatusCurrentTierTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    OncePerRequestFilter authFilter;

    @Autowired
    UserRepository userRepository;

    @MockitoBean
    StripeService stripeService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(authFilter)
                .build();
    }

    // -------------------------------------------------------------------------
    // T1 — Free user: status endpoint omits current_tier (null → non_null omits it)
    // -------------------------------------------------------------------------

    @Test
    void freeUser_statusReturnsNullTier() throws Exception {
        when(stripeService.describePlans()).thenReturn(Map.of());
        when(stripeService.currentTierForUser(any())).thenReturn(null);

        mvc.perform(get("/api/subscription/status")
                        .header("X-User-Email", "free-t1@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.current_tier").doesNotExist())
                .andExpect(jsonPath("$.source").doesNotExist());
    }

    // -------------------------------------------------------------------------
    // T2 — Monthly Pro user: status endpoint returns current_tier == "monthly"
    // -------------------------------------------------------------------------

    @Test
    void monthlyUser_statusReturnsMonthlyTier() throws Exception {
        when(stripeService.describePlans()).thenReturn(Map.of());
        when(stripeService.currentTierForUser(any())).thenReturn("monthly");

        // Seed user row via first request
        mvc.perform(get("/api/subscription/status")
                        .header("X-User-Email", "monthly-t2@example.com"))
                .andExpect(status().isOk()); // seed

        User u = userRepository.findByEmail("monthly-t2@example.com").orElseThrow();
        u.setStripeCustomerId("cus_monthly_test");
        u.setSubscriptionExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        userRepository.save(u);

        mvc.perform(get("/api/subscription/status")
                        .header("X-User-Email", "monthly-t2@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.current_tier").value("monthly"));
    }

    // -------------------------------------------------------------------------
    // T3 — Annual Pro user: status endpoint returns current_tier == "annual"
    // -------------------------------------------------------------------------

    @Test
    void annualUser_statusReturnsAnnualTier() throws Exception {
        when(stripeService.describePlans()).thenReturn(Map.of());
        when(stripeService.currentTierForUser(any())).thenReturn("annual");

        mvc.perform(get("/api/subscription/status")
                        .header("X-User-Email", "annual-t3@example.com"))
                .andExpect(status().isOk()); // seed

        User u = userRepository.findByEmail("annual-t3@example.com").orElseThrow();
        u.setStripeCustomerId("cus_annual_test");
        u.setSubscriptionExpiresAt(Instant.now().plus(365, ChronoUnit.DAYS));
        userRepository.save(u);

        mvc.perform(get("/api/subscription/status")
                        .header("X-User-Email", "annual-t3@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.current_tier").value("annual"));
    }

    // -------------------------------------------------------------------------
    // T4 — Billing source passes through: subscriptionSource on the user shows
    // up as "source" in the response (P1-21: web routes "Manage subscription"
    // to the right portal). Absent case is covered by T1's doesNotExist.
    // -------------------------------------------------------------------------

    @Test
    void subscribedUser_statusReturnsBillingSource() throws Exception {
        when(stripeService.describePlans()).thenReturn(Map.of());
        when(stripeService.currentTierForUser(any())).thenReturn("monthly");

        mvc.perform(get("/api/subscription/status")
                        .header("X-User-Email", "source-t4@example.com"))
                .andExpect(status().isOk()); // seed

        User u = userRepository.findByEmail("source-t4@example.com").orElseThrow();
        u.setSubscriptionExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        u.setSubscriptionSource("apple");
        userRepository.save(u);

        mvc.perform(get("/api/subscription/status")
                        .header("X-User-Email", "source-t4@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.source").value("apple"));
    }
}
