package com.afterduty.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Config-driven LLM routing (Increment 3). Binds {@code va-claim.llm.purposes.<purpose>.{provider,model}}
 * so the per-purpose provider+model mapping is configuration, not hard-code, and any purpose can be
 * flipped to a different provider/model by env var without a code deploy.
 *
 * <p>Example (application.yml):
 * <pre>
 * va-claim:
 *   llm:
 *     purposes:
 *       synthesis_verify:
 *         provider: vertex-anthropic
 *         model: claude-opus-5
 * </pre>
 *
 * <p>The map key is the {@code LlmJobRequest.purpose} string. {@link com.afterduty.service.llm.LlmProviderRouter}
 * reads this at construction to populate its purpose→provider and purpose→model tables; any purpose
 * absent here falls back to the router's last-resort defaults (which now log a WARN, since a miss
 * means a config gap).
 */
@Configuration
@ConfigurationProperties(prefix = "va-claim.llm")
public class LlmRoutingProperties {

    /** purpose → {provider, model}. */
    private Map<String, PurposeRoute> purposes = new LinkedHashMap<>();

    public Map<String, PurposeRoute> getPurposes() { return purposes; }
    public void setPurposes(Map<String, PurposeRoute> purposes) {
        this.purposes = purposes == null ? new LinkedHashMap<>() : purposes;
    }

    /** One purpose's resolved provider name + model id. */
    public static class PurposeRoute {
        /** Provider name, e.g. "vertex-anthropic", "vertex-gemini", "anthropic-batch". */
        private String provider;
        /** Model id, e.g. "claude-sonnet-4-6", "gemini-3.1-pro-preview". */
        private String model;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
    }
}
