package com.afterduty.controller;

import com.afterduty.model.Claim;
import com.afterduty.model.User;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.UserRepository;
import com.afterduty.service.ChatAgent;
import com.afterduty.service.ChatStreamListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Increment 7 §F.2 / §H.2 — ChatStreamController SSE endpoint. MockMvc with H2.
 * Asserts: pre-stream gating errors are plain JSON (402 free owner); the happy path
 * emits ack → delta → message; and the streaming-disabled flag yields 409.
 */
@SpringBootTest
@ActiveProfiles("local")
@Tag("regression")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:chatstreamtest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.cloud.gcp.sql.enabled=false",
        "spring.autoconfigure.exclude=com.google.cloud.spring.autoconfigure.sql.GcpCloudSqlAutoConfiguration,com.google.cloud.spring.autoconfigure.firestore.GcpFirestoreAutoConfiguration,com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration,com.google.cloud.spring.autoconfigure.secretmanager.GcpSecretManagerAutoConfiguration"
})
class ChatStreamControllerTest {

    private static final Instant FUTURE = Instant.now().plus(365, ChronoUnit.DAYS);

    @Autowired WebApplicationContext context;
    @Autowired OncePerRequestFilter authFilter;
    @Autowired ClaimRepository claimRepository;
    @Autowired UserRepository userRepository;

    /** Mock the agent so streaming returns canned text without a live LLM. */
    @MockitoBean ChatAgent chatAgent;

    /**
     * P1-10 — mocked so a test can script the usage cap firing. The default mock is a
     * no-op assertCapacity, which matches the real guard's pass-through for fresh users.
     */
    @MockitoBean com.afterduty.service.UsageGuard usageGuard;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(authFilter).build();
    }

    private User makeOwnerWithClaim(String email, boolean pro) throws Exception {
        // Trigger user + claim creation through the normal claim endpoint.
        mvc.perform(post("/api/claim/chat/stream")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"warmup\"}"))
                .andReturn();
        User u = userRepository.findByEmail(email).orElseThrow();
        if (pro) { u.setSubscriptionExpiresAt(FUTURE); u = userRepository.save(u); }
        // Ensure a claim exists for the user.
        if (claimRepository.findByUserIdOrderByCreatedAtDesc(u.getId()).isEmpty()) {
            claimRepository.save(Claim.builder().userId(u.getId())
                    .claimType(Claim.ClaimType.INITIAL).status(Claim.ClaimStatus.DRAFT).build());
        }
        return u;
    }

    // ---- pre-stream gating (§E.3 owner Pro-gate fires in this controller) ----

    @Test
    void freeOwner_getsPlainJson402_subscriptionRequired() throws Exception {
        String email = "free-owner-" + System.nanoTime() + "@test.com";
        makeOwnerWithClaim(email, /*pro=*/false);

        mvc.perform(post("/api/claim/chat/stream")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isPaymentRequired());   // 402, plain JSON — not an SSE stream
    }

    // P1-10 — a PAYING user at the monthly usage cap must be wire-DISTINGUISHABLE from
    // the subscription 402 above (the BFF maps any 402 → "subscribe", which is
    // unactionable for someone already paying): 429 + code + resumesAt.
    @Test
    void proOwner_atUsageCap_gets429_withUsageLimitCode_andResumesAt() throws Exception {
        String email = "capped-pro-owner-" + System.nanoTime() + "@test.com";
        makeOwnerWithClaim(email, /*pro=*/true);

        Instant periodEnd = Instant.parse("2026-08-01T00:00:00Z");
        org.mockito.Mockito.doThrow(new com.afterduty.exception.UsageLimitException(periodEnd))
                .when(usageGuard).assertCapacity(org.mockito.ArgumentMatchers.anyLong());

        mvc.perform(post("/api/claim/chat/stream")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isTooManyRequests())   // NOT 402 — the cap is quota, not payment
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("USAGE_LIMIT_REACHED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.resumesAt").value("2026-08-01T00:00:00Z"));
    }

    // ---- happy path: ack → delta → message ----

    @Test
    void proOwner_happyPath_emitsAckDeltaMessage() throws Exception {
        String email = "pro-owner-" + System.nanoTime() + "@test.com";
        makeOwnerWithClaim(email, /*pro=*/true);

        // Agent drives the listener: one delta, then returns the final text.
        when(chatAgent.handleStreaming(any(), anyLong(), anyLong(), anyLong(), any()))
                .thenAnswer((InvocationOnMock inv) -> {
                    ChatStreamListener l = inv.getArgument(4);
                    l.onStatus("thinking", null);
                    l.onDelta("Hello");
                    l.onDelta(" world");
                    return "Hello world";
                });

        MvcResult result = mvc.perform(post("/api/claim/chat/stream")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // Drain the async SSE result.
        String body = mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("event:ack");
        assertThat(body).contains("event:delta");
        assertThat(body).contains("Hello");
        assertThat(body).contains("event:message");
        // The canonical assistant content rides the terminal message event.
        assertThat(body).contains("Hello world");
    }

    // P1-10 — provider brownout (agent's bounded retries exhausted) reaches the wire as
    // the RETRYABLE error {code:"rate_limited"}, not the generic agent_error, and the
    // capacity-specific fallback copy is what persists.
    @Test
    void proOwner_agentRateLimited_emitsRateLimitedErrorEvent() throws Exception {
        String email = "ratelimited-pro-owner-" + System.nanoTime() + "@test.com";
        makeOwnerWithClaim(email, /*pro=*/true);

        when(chatAgent.handleStreaming(any(), anyLong(), anyLong(), anyLong(), any()))
                .thenThrow(new com.afterduty.service.ChatAgent.ChatRateLimitedException(
                        "rate-limited 3 times in a row — giving up", null));

        MvcResult result = mvc.perform(post("/api/claim/chat/stream")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("event:error");
        assertThat(body).contains("rate_limited");
        assertThat(body).doesNotContain("agent_error");
    }

    // Contrast: a non-rate-limit agent failure still classifies as agent_error.
    @Test
    void proOwner_agentGenericFailure_stillEmitsAgentError() throws Exception {
        String email = "broken-pro-owner-" + System.nanoTime() + "@test.com";
        makeOwnerWithClaim(email, /*pro=*/true);

        when(chatAgent.handleStreaming(any(), anyLong(), anyLong(), anyLong(), any()))
                .thenThrow(new RuntimeException("LLM down"));

        MvcResult result = mvc.perform(post("/api/claim/chat/stream")
                        .header("X-User-Email", email)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("event:error");
        assertThat(body).contains("agent_error");
    }

    // Note: the streaming-disabled ⇒ 409 case lives in ChatStreamControllerFlagOffTest,
    // because va-claim.chat.streaming is read at bean init and needs its own context.
}
