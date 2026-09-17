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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("local")
@Tag("regression")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:portaltest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.cloud.gcp.sql.enabled=false",
        "spring.autoconfigure.exclude=com.google.cloud.spring.autoconfigure.sql.GcpCloudSqlAutoConfiguration,com.google.cloud.spring.autoconfigure.firestore.GcpFirestoreAutoConfiguration,com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration,com.google.cloud.spring.autoconfigure.secretmanager.GcpSecretManagerAutoConfiguration"
})
class SubscriptionPortalTest {

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
    // T4 — Pro user: portal endpoint returns 200 with billing URL
    // -------------------------------------------------------------------------

    @Test
    void proUser_portalReturns200WithUrl() throws Exception {
        when(stripeService.describePlans()).thenReturn(Map.of());
        when(stripeService.createPortalSession(any()))
                .thenReturn(Map.of("url", "https://billing.stripe.com/test"));

        // Seed user row and set stripeCustomerId
        mvc.perform(get("/api/subscription/status")
                        .header("X-User-Email", "pro-t4@example.com"))
                .andExpect(status().isOk()); // seed

        User u = userRepository.findByEmail("pro-t4@example.com").orElseThrow();
        u.setStripeCustomerId("cus_pro_test");
        u.setSubscriptionExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        userRepository.save(u);

        mvc.perform(post("/api/subscription/portal")
                        .header("X-User-Email", "pro-t4@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://billing.stripe.com/test"));
    }

    // -------------------------------------------------------------------------
    // T5 — Free user: portal endpoint returns 400 with no_stripe_customer
    // -------------------------------------------------------------------------

    @Test
    void freeUser_portalReturns400WithNoStripeCustomer() throws Exception {
        when(stripeService.createPortalSession(any()))
                .thenThrow(new IllegalArgumentException("no_stripe_customer"));

        mvc.perform(post("/api/subscription/portal")
                        .header("X-User-Email", "free-t5@example.com"))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason(containsString("no_stripe_customer")));
    }
}
