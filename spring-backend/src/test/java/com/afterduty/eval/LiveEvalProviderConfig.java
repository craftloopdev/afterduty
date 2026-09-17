package com.afterduty.eval;

import com.afterduty.config.LlmRoutingProperties;
import com.afterduty.service.AiCostService;
import com.afterduty.service.llm.LlmAsyncProvider;
import com.afterduty.service.llm.LlmProviderRouter;
import com.afterduty.service.llm.VertexAnthropicProviderImpl;
import com.afterduty.service.llm.VertexGeminiAsyncProviderImpl;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * Live-tier provider wiring (spec §3.1). Swaps the offline fake for the REAL Vertex
 * lanes so {@link LiveGoldenEvalTest} measures what production runs:
 *
 * <ul>
 *   <li>{@link VertexAnthropicProviderImpl} + {@link VertexGeminiAsyncProviderImpl}
 *       built from ADC (same env contract as {@code VertexAnthropicLiveSmokeTest}:
 *       {@code GOOGLE_APPLICATION_CREDENTIALS}, {@code GCP_PROJECT}).</li>
 *   <li>a real {@link LlmProviderRouter} bound to {@link LlmRoutingProperties} — by
 *       default the PRODUCTION routing table from {@code application.yml} so the eval
 *       measures prod; {@code ROUTE_*} env overrides are the candidate-model lever.</li>
 *   <li>a real {@link AiCostService} so every call books an {@code AiCallLog} row —
 *       the run's cost ledger and the spend-cap input.</li>
 * </ul>
 *
 * <p><b>Realtime pins:</b> the eval profile forces any {@code anthropic-batch}-routed
 * purpose onto {@code vertex-anthropic} (a ≤24h batch turnaround inside a test is
 * absurd). Consequence stated in the report: live eval measures model output
 * quality, not batch-lane mechanics.
 *
 * <p>This config is intentionally a structural scaffold: the env-bound provider
 * construction + cost wiring is the operator's first-run step (spec §8 "Live
 * (manual, before first merge)"), since none of it can be verified in offline CI.
 */
@TestConfiguration
public class LiveEvalProviderConfig {

    @Bean
    public VertexAnthropicProviderImpl liveAnthropicProvider() {
        VertexAnthropicProviderImpl p = new VertexAnthropicProviderImpl();
        // projectId/region/defaultModelName resolve from va-claim.* test properties
        // (or env). init() builds the Vertex client from ADC on first use.
        return p;
    }

    @Bean
    public VertexGeminiAsyncProviderImpl liveGeminiProvider() {
        return new VertexGeminiAsyncProviderImpl();
    }

    /**
     * Real router over the live providers, bound to the production routing table.
     * Realtime-pin enforcement (batch → realtime anthropic) is applied by the routing
     * properties the eval profile loads; see the class javadoc.
     */
    @Bean
    @Primary
    public LlmProviderRouter liveLlmProviderRouter(List<LlmAsyncProvider> providers,
                                                   LlmRoutingProperties routing) {
        return new LlmProviderRouter(providers, routing);
    }

    @Bean
    @Primary
    public AiCostService liveAiCostService(
            com.afterduty.repository.AiCallLogRepository repo) {
        // The real cost service prices the regional premium + cache columns so each
        // call books a real AiCallLog row — the run's cost ledger.
        return new AiCostService(repo);
    }
}
