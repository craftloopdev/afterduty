package com.afterduty.service.llm;

import com.afterduty.config.LlmRoutingProperties;
import com.afterduty.config.LlmRoutingProperties.PurposeRoute;
import com.afterduty.model.LlmJob;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Increment 3 coverage: proves {@link LlmProviderRouter} resolves purpose → (provider, model) from
 * {@link LlmRoutingProperties} (the {@code va-claim.llm.purposes.*} config), not from hard-code.
 *
 * <p>Pure unit test — no Spring context. Stub providers implement the three marker interfaces so the
 * router can look them up by name. Tagged regression for the pre-prod suite.
 */
@Tag("regression")
class LlmProviderRouterConfigTest {

    // --- minimal stub providers, one per registered provider name -------------------------------
    static final class FakeVertexAnthropic implements VertexAnthropicProvider {
        public String providerName() { return VertexAnthropicProvider.NAME; }
        public List<LlmJobHandle> submit(List<LlmJob> jobs) { return List.of(); }
        public ProviderJobStatus poll(String id) { return ProviderJobStatus.SUCCEEDED; }
        public List<FetchedResult> fetchResults(String id, List<LlmJob> jobs) { return List.of(); }
    }
    static final class FakeVertexGemini implements VertexGeminiAsyncProvider {
        public String providerName() { return VertexGeminiAsyncProvider.NAME; }
        public List<LlmJobHandle> submit(List<LlmJob> jobs) { return List.of(); }
        public ProviderJobStatus poll(String id) { return ProviderJobStatus.SUCCEEDED; }
        public List<FetchedResult> fetchResults(String id, List<LlmJob> jobs) { return List.of(); }
    }
    static final class FakeAnthropicBatch implements AnthropicBatchProvider {
        public String providerName() { return AnthropicBatchProvider.NAME; }
        public List<LlmJobHandle> submit(List<LlmJob> jobs) { return List.of(); }
        public ProviderJobStatus poll(String id) { return ProviderJobStatus.SUCCEEDED; }
        public List<FetchedResult> fetchResults(String id, List<LlmJob> jobs) { return List.of(); }
    }

    private LlmProviderRouter routerWith(Map<String, PurposeRoute> purposes) {
        LlmRoutingProperties props = new LlmRoutingProperties();
        props.setPurposes(purposes);
        return new LlmProviderRouter(
                List.of(new FakeVertexAnthropic(), new FakeVertexGemini(), new FakeAnthropicBatch()),
                props);
    }

    private static PurposeRoute route(String provider, String model) {
        PurposeRoute r = new PurposeRoute();
        r.setProvider(provider);
        r.setModel(model);
        return r;
    }

    private static LlmJobRequest req(String purpose) {
        return LlmJobRequest.builder().purpose(purpose).userMessage("x").build();
    }

    @Test
    void config_routesSynthesisVerify_toVertexAnthropicOpus() {
        Map<String, PurposeRoute> p = new LinkedHashMap<>();
        p.put("synthesis_verify", route("vertex-anthropic", "claude-opus-4-8"));

        LlmProviderRouter router = routerWith(p);
        LlmJobRequest r = req("synthesis_verify");

        LlmAsyncProvider provider = router.resolveProvider(r);
        assertEquals(VertexAnthropicProvider.NAME, provider.providerName());
        assertEquals("claude-opus-4-8", router.resolveModel(r, provider));
    }

    @Test
    void config_routesGapAndChat_toVertexAnthropicSonnet() {
        Map<String, PurposeRoute> p = new LinkedHashMap<>();
        p.put("gap_evidence", route("vertex-anthropic", "claude-sonnet-4-6"));
        p.put("chat", route("vertex-anthropic", "claude-sonnet-4-6"));

        LlmProviderRouter router = routerWith(p);

        for (String purpose : List.of("gap_evidence", "chat")) {
            LlmJobRequest r = req(purpose);
            LlmAsyncProvider provider = router.resolveProvider(r);
            assertEquals(VertexAnthropicProvider.NAME, provider.providerName(), purpose);
            assertEquals("claude-sonnet-4-6", router.resolveModel(r, provider), purpose);
        }
    }

    @Test
    void config_deviationClosed_synthesisRate_routesToVertexAnthropicSonnet() {
        // Mission 6b — the deviation is CLOSED. With the cached atom-corpus block,
        // Sonnet rate is now cheaper than a Gemini full-resend, so the design route
        // (vertex-anthropic / claude-sonnet-4-6) is the default the router resolves.
        Map<String, PurposeRoute> p = new LinkedHashMap<>();
        p.put("synthesis_rate", route("vertex-anthropic", "claude-sonnet-4-6"));

        LlmProviderRouter router = routerWith(p);
        LlmJobRequest r = req("synthesis_rate");

        LlmAsyncProvider provider = router.resolveProvider(r);
        assertEquals(VertexAnthropicProvider.NAME, provider.providerName());
        assertEquals("claude-sonnet-4-6", router.resolveModel(r, provider));
    }

    @Test
    void config_synthesisRate_geminiRemainsAValidRollback() {
        // Gemini stays a one-env-var rollback (ROUTE_SYNTHESIS_RATE_*): the overlay
        // still honors it if a deploy points the purpose back.
        Map<String, PurposeRoute> p = new LinkedHashMap<>();
        p.put("synthesis_rate", route("vertex-gemini", "gemini-3.1-pro-preview"));

        LlmProviderRouter router = routerWith(p);
        LlmJobRequest r = req("synthesis_rate");

        LlmAsyncProvider provider = router.resolveProvider(r);
        assertEquals(VertexGeminiAsyncProvider.NAME, provider.providerName());
        assertEquals("gemini-3.1-pro-preview", router.resolveModel(r, provider));
    }

    @Test
    void config_rollbackLane_canFlipPurposeBackToAnthropicBatch() {
        // Rollback flag: any purpose can be re-pointed at anthropic-batch by config alone.
        Map<String, PurposeRoute> p = new LinkedHashMap<>();
        p.put("gap_validation", route("anthropic-batch", "claude-opus-4-7"));

        LlmProviderRouter router = routerWith(p);
        LlmJobRequest r = req("gap_validation");

        LlmAsyncProvider provider = router.resolveProvider(r);
        assertEquals(AnthropicBatchProvider.NAME, provider.providerName());
        assertEquals("claude-opus-4-7", router.resolveModel(r, provider));
    }

    @Test
    void preferredOverridesOnRequest_winOverConfig() {
        Map<String, PurposeRoute> p = new LinkedHashMap<>();
        p.put("synthesis_identify", route("vertex-gemini", "gemini-3.1-pro-preview"));

        LlmProviderRouter router = routerWith(p);
        LlmJobRequest r = LlmJobRequest.builder()
                .purpose("synthesis_identify")
                .userMessage("x")
                .preferredProvider(VertexAnthropicProvider.NAME)
                .preferredModel("claude-sonnet-4-6")
                .build();

        LlmAsyncProvider provider = router.resolveProvider(r);
        assertEquals(VertexAnthropicProvider.NAME, provider.providerName());
        assertEquals("claude-sonnet-4-6", router.resolveModel(r, provider));
    }

    @Test
    void noConfigForPurpose_fallsBackToProviderDefault_withWarnPath() {
        // Empty config → router uses its hard-coded baseline provider, and resolveModel hits the
        // last-resort defaultModelFor (WARN-logged). synthesis_identify baseline = vertex-gemini.
        LlmProviderRouter router = routerWith(new LinkedHashMap<>());
        LlmJobRequest r = req("synthesis_identify");

        LlmAsyncProvider provider = router.resolveProvider(r);
        assertEquals(VertexGeminiAsyncProvider.NAME, provider.providerName());
        // purposeDefaultModels is empty → falls back to defaultModelFor(vertex-gemini)
        assertEquals("gemini-3.1-pro-preview", router.resolveModel(r, provider));
    }

    @Test
    void legacySingleArgConstructor_stillCompilesAndRoutesOnBaseline() {
        // The test substrate uses `new LlmProviderRouter(List.of(fake))`; that constructor must
        // remain and route on the hard-coded baseline (no config overlay).
        LlmProviderRouter router = new LlmProviderRouter(
                List.of(new FakeVertexAnthropic(), new FakeVertexGemini(), new FakeAnthropicBatch()));
        LlmJobRequest r = req("gap_evidence");
        // baseline gap_evidence = anthropic-batch
        assertEquals(AnthropicBatchProvider.NAME, router.resolveProvider(r).providerName());
    }
}
