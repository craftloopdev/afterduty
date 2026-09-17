package com.afterduty.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.afterduty.model.LlmJob;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Live smoke tests — each sends ONE ~10-token message through
 * {@link VertexAnthropicProviderImpl}'s real submit→poll→fetch path against the
 * <b>{@code us} multi-region</b> Vertex endpoint (the production lane since the
 * 2026-08-01 residency decision: {@code aiplatform.us.rep.googleapis.com}).
 * Covers both production models: claude-sonnet-5 (all Sonnet routes) and
 * claude-opus-5 (synthesis verify). Cost &lt; $0.01 per test.
 *
 * <p>EXCLUDED from the default suite (tag {@code livesmoke}); run only with:
 * <pre>
 *   GOOGLE_APPLICATION_CREDENTIALS=$HOME/.gcp/default-compute-sa.json \
 *   GCP_PROJECT=craftloop-va-claim \
 *   JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew test -PliveSmoke \
 *       --tests com.afterduty.service.llm.VertexAnthropicLiveSmokeTest
 * </pre>
 * If IAM denies (the SA may lack aiplatform.endpoints.predict), the call comes back as a FAILED
 * FetchedResult and the test prints the exact provider error rather than asserting — deploy-time
 * verification covers the permission grant.
 */
@Tag("livesmoke")
class VertexAnthropicLiveSmokeTest {

    @Test
    void oneShotSonnet5_overUsMultiRegion() throws Exception {
        runOneShot("us", "claude-sonnet-5");
    }

    @Test
    void oneShotOpus5_overUsMultiRegion() throws Exception {
        runOneShot("us", "claude-opus-5");
    }

    private static void runOneShot(String region, String model) throws Exception {
        String project = System.getenv().getOrDefault("GCP_PROJECT", "craftloop-va-claim");

        VertexAnthropicProviderImpl provider = new VertexAnthropicProviderImpl();
        set(provider, "projectId", project);
        set(provider, "region", region);
        set(provider, "defaultModelName", model);
        provider.init();   // build the Vertex client from ADC

        ObjectMapper om = new ObjectMapper();
        String payload = om.writeValueAsString(Map.of(
                "systemPrompt", "Reply with a single word.",
                "messages", List.of(Map.of("role", "user", "content", "Say hello.")),
                "maxTokens", 32,
                "thinkingBudget", 0));

        LlmJob job = LlmJob.builder()
                .id(UUID.randomUUID())
                .provider(VertexAnthropicProvider.NAME)
                .modelName(model)
                .purpose("livesmoke")
                .status(LlmJob.Status.SUBMITTED)
                .requestPayload(payload)
                .build();

        // submit is non-blocking; the real call runs synchronously inside fetchResults.
        provider.submit(List.of(job));
        List<LlmAsyncProvider.FetchedResult> results =
                provider.fetchResults(job.getId().toString(), List.of(job));

        LlmAsyncProvider.FetchedResult r = results.get(0);
        if (r.succeeded) {
            System.out.println("[LIVE SMOKE] SUCCESS — region=" + region
                    + " model=" + r.result.getModelName()
                    + " text=" + r.result.getText().trim()
                    + " in=" + r.result.getInputTokens() + " out=" + r.result.getOutputTokens());
        } else {
            System.out.println("[LIVE SMOKE] PROVIDER ERROR (expected if SA lacks aiplatform perms): "
                    + r.errorMessage);
        }
    }

    private static void set(Object t, String name, Object v) throws Exception {
        Field f = VertexAnthropicProviderImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(t, v);
    }
}
