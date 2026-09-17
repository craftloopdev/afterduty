package com.afterduty.service.llm;

import com.afterduty.service.AiCostService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * Supplies test beans that replace real provider + cost implementations.
 * Imported by test classes that need the full LlmJobService/Submitter/Poller
 * pipeline running against H2 with no external HTTP calls.
 */
@TestConfiguration
public class LlmProviderRouterTestConfig {

    @Bean
    @Primary
    public FakeLlmAsyncProvider fakeLlmAsyncProvider() {
        return new FakeLlmAsyncProvider();
    }

    /**
     * Router wired with ONLY the fake provider. All purposes will be routed
     * to it because AnthropicBatchProvider.NAME and VertexGeminiAsyncProvider.NAME
     * are not registered here — the router falls back to the only available
     * provider. The fake's providerName() is "fake-llm" which won't match
     * any purposeDefault entry, so we must force routing via preferredProvider
     * or accept the fallback-warn path. In practice the fake provider is
     * registered under "fake-llm", but the purpose-defaults map points to
     * "anthropic-batch" and "vertex-gemini" which are not in the registry.
     *
     * Solution: subclass the router in-test to return the fake for every request.
     */
    @Bean
    @Primary
    public LlmProviderRouter testLlmProviderRouter(FakeLlmAsyncProvider fake) {
        return new LlmProviderRouter(List.of(fake)) {
            @Override
            public LlmAsyncProvider resolveProvider(LlmJobRequest req) {
                return fake;
            }

            @Override
            public LlmAsyncProvider byName(String providerName) {
                return fake;
            }

            @Override
            public String resolveModel(LlmJobRequest req, LlmAsyncProvider provider) {
                return "fake-model-1";
            }
        };
    }

    @Bean
    @Primary
    public AiCostService noopAiCostService() {
        // Null repository is safe here because we override the methods that use it.
        return new AiCostService(null) {
            @Override
            public com.afterduty.model.AiCallLog recordCall(com.afterduty.model.AiCallLog logRow) {
                return logRow; // no-op in tests — don't hit the null repository
            }
            @Override
            public com.afterduty.model.AiCallLog recordError(com.afterduty.model.AiCallLog logRow, String error) {
                return logRow; // no-op in tests
            }
        };
    }
}
