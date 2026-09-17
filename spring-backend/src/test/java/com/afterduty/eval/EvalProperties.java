package com.afterduty.eval;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Test-scoped binding of the {@code va-claim.eval.*} config (spec §3.2). These keys
 * live in TEST scope only (an {@code @TestPropertySource} on the live runner), NOT
 * in {@code application.yml} — the eval harness must add zero production config
 * surface. Every value is env-overridable.
 */
@ConfigurationProperties(prefix = "va-claim.eval")
public class EvalProperties {

    private Live live = new Live();

    public Live getLive() { return live; }
    public void setLive(Live live) { this.live = live; }

    public static class Live {
        /** Hard abort for the whole run, judge included (USD). */
        private double maxSpendUsd = 15.00;
        /** Per-case wall-clock budget (minutes). */
        private int perCaseTimeoutMin = 10;
        private Judge judge = new Judge();

        public double getMaxSpendUsd() { return maxSpendUsd; }
        public void setMaxSpendUsd(double v) { this.maxSpendUsd = v; }
        public int getPerCaseTimeoutMin() { return perCaseTimeoutMin; }
        public void setPerCaseTimeoutMin(int v) { this.perCaseTimeoutMin = v; }
        public Judge getJudge() { return judge; }
        public void setJudge(Judge judge) { this.judge = judge; }
    }

    public static class Judge {
        /** Cross-family: Gemini judges Claude output. */
        private String provider = "vertex-gemini";
        private String model = "gemini-3.1-pro-preview";
        private double temperature = 0.0;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
    }
}
