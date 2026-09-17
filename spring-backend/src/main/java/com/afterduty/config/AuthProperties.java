package com.afterduty.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Auth-program config (P1.6). Lives in the existing {@code va-claim.auth.*}
 * namespace alongside {@code va-claim.auth.dev-mode}.
 *
 * <p><b>{@code va-claim.auth.mfa-model}</b> (env {@code AUTH_MFA_MODEL}) — the
 * MFA strictness model ratified 2026-07-04 (plan D1):
 * <ul>
 *   <li>{@code ENROLLMENT_DEVICE_TRUST} (<b>default</b>, Model B) — OTP + factor-2
 *       bind the device at enrollment; thereafter one gesture unlocks the session,
 *       with step-up required for sensitive actions and on new/untrusted devices.</li>
 *   <li>{@code STRICT} (Model A) — step-up is required for every sensitive action
 *       regardless of device trust.</li>
 * </ul>
 * Read in the step-up layer ({@code StepUpService}) so tightening later is a
 * config change, not a code change. Unknown values fail safe to
 * {@code ENROLLMENT_DEVICE_TRUST}.
 *
 * <p>Only OTP step-up exists in Phase 1; the flag is wired now so the guard's
 * decision point already reads it.
 */
@Configuration
@ConfigurationProperties(prefix = "va-claim.auth")
public class AuthProperties {

    public enum MfaModel {
        /** Model B (default): enrollment MFA + device trust; step-up on sensitive actions / new devices. */
        ENROLLMENT_DEVICE_TRUST,
        /** Model A: step-up on every sensitive action. */
        STRICT
    }

    private MfaModel mfaModel = MfaModel.ENROLLMENT_DEVICE_TRUST;

    /** WebAuthn / passkey RP config (P1.3). Bound from {@code va-claim.auth.webauthn.*}. */
    private final Webauthn webauthn = new Webauthn();

    public MfaModel getMfaModel() {
        return mfaModel;
    }

    public Webauthn getWebauthn() {
        return webauthn;
    }

    /**
     * WebAuthn Relying Party settings (auth program P1.3). Defaults are the
     * production values; each is overridable via an env var for dev/preview:
     * <ul>
     *   <li>{@code rpId} — the RP ID (an origin's registrable domain). Default
     *       {@code afterduty.app}; override {@code WEBAUTHN_RP_ID}.</li>
     *   <li>{@code rpName} — human-readable RP name shown in the OS prompt.
     *       Default {@code After Duty}.</li>
     *   <li>{@code origin} — the exact expected origin (scheme + host + optional
     *       port); the assertion's origin must match. Default
     *       {@code https://app.afterduty.app}; override {@code WEBAUTHN_ORIGIN}.</li>
     * </ul>
     * The {@code rpId} MUST be a registrable suffix of the {@code origin}'s host
     * (WebAuthn requirement) — the RP builder enforces this at ceremony time.
     */
    public static class Webauthn {
        private String rpId = "afterduty.app";
        private String rpName = "After Duty";
        private String origin = "https://app.afterduty.app";

        public String getRpId() {
            return rpId;
        }

        public void setRpId(String rpId) {
            if (rpId != null && !rpId.isBlank()) {
                this.rpId = rpId.trim();
            }
        }

        public String getRpName() {
            return rpName;
        }

        public void setRpName(String rpName) {
            if (rpName != null && !rpName.isBlank()) {
                this.rpName = rpName.trim();
            }
        }

        public String getOrigin() {
            return origin;
        }

        public void setOrigin(String origin) {
            if (origin != null && !origin.isBlank()) {
                this.origin = origin.trim();
            }
        }
    }

    /**
     * Binds from {@code va-claim.auth.mfa-model}. Accepts the enum name in any
     * case; anything unrecognized (or blank) fails safe to the default Model B.
     */
    public void setMfaModel(String value) {
        if (value == null || value.isBlank()) {
            this.mfaModel = MfaModel.ENROLLMENT_DEVICE_TRUST;
            return;
        }
        try {
            this.mfaModel = MfaModel.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            this.mfaModel = MfaModel.ENROLLMENT_DEVICE_TRUST;
        }
    }

    /** True when the strict model (Model A) is active: step-up on every sensitive action. */
    public boolean isStrict() {
        return mfaModel == MfaModel.STRICT;
    }
}
