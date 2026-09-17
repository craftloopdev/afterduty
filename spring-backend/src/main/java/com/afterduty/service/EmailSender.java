package com.afterduty.service;

/**
 * Outbound transactional-email abstraction for the passwordless-OTP flow
 * (passwordless-otp-auth-spec §5.1). Kept deliberately tiny: the OTP path sends
 * one HTML body to one address and branches only on whether the send succeeded.
 *
 * <p>Mirrors VolunTails' {@code notification_service.send_email} contract: no
 * {@code User} lookup and no notification-log row for OTP sends (no
 * timing/enumeration oracle, no PII trail of who got a code).
 */
public interface EmailSender {

    enum Status {
        /** Accepted by the provider (2xx). */
        SENT,
        /** Provider not configured — intentionally not sent (dev grace, §5.4). */
        SKIPPED,
        /** Provider threw or returned a non-2xx. */
        FAILED
    }

    record Result(Status status, String externalId, String errorMessage) {
        public static Result sent(String externalId) {
            return new Result(Status.SENT, externalId, null);
        }

        public static Result skipped() {
            return new Result(Status.SKIPPED, null, null);
        }

        public static Result failed(String errorMessage) {
            return new Result(Status.FAILED, null, errorMessage);
        }
    }

    /**
     * Send an HTML email. Implementations MUST NOT log the body (it carries the
     * one-time code). Returns a {@link Result}; callers branch on
     * {@link Status} rather than catching.
     */
    Result sendHtml(String toEmail, String subject, String htmlBody);
}
