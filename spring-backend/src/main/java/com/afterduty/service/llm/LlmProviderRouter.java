package com.afterduty.service.llm;

import com.afterduty.config.LlmRoutingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a (purpose, preferred provider, preferred model) to the concrete
 * {@link LlmAsyncProvider} that should run the request. Centralizing this
 * here means the orchestrators never look at provider names; swapping a
 * purpose to a different vendor is a one-line change.
 *
 * <p>Routing precedence:
 * <ol>
 *     <li>Explicit {@code preferredProvider} on the request, if set</li>
 *     <li>Model name prefix ({@code claude-*} → Anthropic, {@code gemini-*} → Vertex)</li>
 *     <li>Per-purpose table — populated from {@code va-claim.llm.purposes.*} config (Increment 3),
 *         overlaid on the legacy hard-coded baseline</li>
 *     <li>Fallback default ({@code vertex-gemini}) — now logged at WARN, since a miss means a
 *         config gap rather than an intended default</li>
 * </ol>
 *
 * <p><b>Increment 3 (config-driven routing):</b> the {@code purposeDefaults} (provider) and
 * {@code purposeDefaultModels} (model) tables are no longer pure hard-code. The legacy table is
 * still seeded as a baseline so the app routes sanely with no config, then
 * {@link LlmRoutingProperties} (bound from {@code va-claim.llm.purposes.<purpose>.{provider,model}})
 * is overlaid on top. This is what makes a purpose flippable to a different provider/model — e.g.
 * rolling a purpose back to {@code anthropic-batch}, or flipping {@code synthesis_rate} to Sonnet
 * once Increment 6 caching lands — by env var, with no code deploy.
 */
@Component
public class LlmProviderRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderRouter.class);

    private final Map<String, LlmAsyncProvider> providersByName = new HashMap<>();
    private final Map<String, String> purposeDefaults = new HashMap<>();
    private final Map<String, String> purposeDefaultModels = new HashMap<>();

    /** Spring constructor — providers auto-collected, routing overlaid from config. */
    @org.springframework.beans.factory.annotation.Autowired
    public LlmProviderRouter(List<LlmAsyncProvider> providers, LlmRoutingProperties routing) {
        for (LlmAsyncProvider p : providers) {
            providersByName.put(p.providerName(), p);
        }

        // --- Baseline provider table (sane defaults if no config is present at all) ---
        // Mirrors the legacy sync-pipeline routing; the config overlay below is authoritative.
        purposeDefaults.put("synthesis_identify",         VertexGeminiAsyncProvider.NAME);
        purposeDefaults.put("synthesis_duplicate_merger", AnthropicBatchProvider.NAME);
        purposeDefaults.put("synthesis_rate",             VertexGeminiAsyncProvider.NAME);
        purposeDefaults.put("synthesis_verify",           VertexGeminiAsyncProvider.NAME);
        purposeDefaults.put("gap_evidence",               AnthropicBatchProvider.NAME);
        purposeDefaults.put("gap_validation",             AnthropicBatchProvider.NAME);
        purposeDefaults.put("gap_whatif",                 VertexGeminiAsyncProvider.NAME);
        purposeDefaults.put("extraction_diagnosis",       VertexGeminiAsyncProvider.NAME);
        purposeDefaults.put("extraction_medication",      VertexGeminiAsyncProvider.NAME);
        purposeDefaults.put("extraction_event",           VertexGeminiAsyncProvider.NAME);
        purposeDefaults.put("extraction_event_segment",   VertexGeminiAsyncProvider.NAME);
        purposeDefaults.put("extraction_service_record",  VertexGeminiAsyncProvider.NAME);
        purposeDefaults.put("extraction_atom",            VertexGeminiAsyncProvider.NAME);
        // Single-pass structured extraction (Mission B): one schema'd call per doc.
        purposeDefaults.put("extraction_doc",             VertexGeminiAsyncProvider.NAME);

        // --- Config overlay (Increment 3): authoritative purpose→provider AND purpose→model ---
        if (routing != null && routing.getPurposes() != null) {
            routing.getPurposes().forEach((purpose, route) -> {
                if (route == null) return;
                if (route.getProvider() != null && !route.getProvider().isBlank()) {
                    purposeDefaults.put(purpose, route.getProvider().trim());
                }
                if (route.getModel() != null && !route.getModel().isBlank()) {
                    purposeDefaultModels.put(purpose, route.getModel().trim());
                }
            });
            log.info("LlmProviderRouter: loaded config routing for {} purpose(s): {}",
                    routing.getPurposes().size(), purposeDefaultModels.keySet());
        } else {
            log.warn("LlmProviderRouter: no va-claim.llm.purposes config present — routing on "
                    + "hard-coded baseline only (purposeDefaultModels empty; per-purpose model "
                    + "selection will hit the last-resort defaultModelFor fallback).");
        }
    }

    /**
     * Legacy/test constructor: providers only, no config overlay. Kept so existing test wiring
     * ({@code new LlmProviderRouter(List.of(fake))}) compiles unchanged. Production always uses the
     * two-arg constructor via Spring.
     */
    public LlmProviderRouter(List<LlmAsyncProvider> providers) {
        this(providers, null);
    }

    public LlmAsyncProvider resolveProvider(LlmJobRequest req) {
        String name = req.getPreferredProvider();
        if (name == null && req.getPreferredModel() != null) {
            name = providerForModel(req.getPreferredModel());
        }
        if (name == null) name = purposeDefaults.get(req.getPurpose());
        if (name == null) {
            log.warn("No provider mapping for purpose '{}', defaulting to {}", req.getPurpose(),
                    VertexGeminiAsyncProvider.NAME);
            name = VertexGeminiAsyncProvider.NAME;
        }
        LlmAsyncProvider p = providersByName.get(name);
        if (p == null) {
            throw new IllegalStateException("No provider registered with name " + name);
        }
        return p;
    }

    public LlmAsyncProvider byName(String providerName) {
        LlmAsyncProvider p = providersByName.get(providerName);
        if (p == null) {
            throw new IllegalStateException("Unknown provider " + providerName);
        }
        return p;
    }

    /** Resolve the model name to use for a request when none is explicit. */
    public String resolveModel(LlmJobRequest req, LlmAsyncProvider provider) {
        if (req.getPreferredModel() != null && !req.getPreferredModel().isBlank()) {
            return req.getPreferredModel();
        }
        String configured = purposeDefaultModels.get(req.getPurpose());
        if (configured != null) {
            return configured;
        }
        // No per-purpose model in config — this masks a config gap. WARN so it's visible.
        String fallback = defaultModelFor(provider);
        log.warn("No configured model for purpose '{}' (provider {}); falling back to last-resort "
                + "default '{}'. Add va-claim.llm.purposes.{}.model to make routing explicit.",
                req.getPurpose(), provider.providerName(), fallback, req.getPurpose());
        return fallback;
    }

    private String defaultModelFor(LlmAsyncProvider p) {
        return switch (p.providerName()) {
            case AnthropicBatchProvider.NAME -> "claude-opus-4-7";
            case VertexAnthropicProvider.NAME -> "claude-sonnet-5";
            case VertexGeminiAsyncProvider.NAME -> "gemini-3.1-pro-preview";
            default -> "unknown";
        };
    }

    private static String providerForModel(String model) {
        // Prefix fallback for the (currently unused) per-call preferredModel escalation hook.
        // Claude now defaults to the Vertex realtime path (no ANTHROPIC_API_KEY); flip back to
        // anthropic-batch explicitly via preferredProvider or the purpose config if the 50% batch
        // lane is wanted for a one-off.
        if (model.startsWith("claude")) return VertexAnthropicProvider.NAME;
        if (model.startsWith("gemini")) return VertexGeminiAsyncProvider.NAME;
        return null;
    }
}
