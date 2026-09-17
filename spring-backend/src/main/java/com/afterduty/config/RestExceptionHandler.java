package com.afterduty.config;

import com.afterduty.exception.ApiException;
import com.afterduty.exception.RecoveryNeedsSupportException;
import com.afterduty.exception.StepUpRequiredException;
import com.afterduty.exception.UsageLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

@RestControllerAdvice
public class RestExceptionHandler {

    /**
     * P0-2 — a multipart upload over the spring.servlet.multipart limits used to
     * surface as a generic 500 the web rendered as "Upload failed. Please try
     * again." (an infinite retry loop). Return 413 with a human message instead
     * (UploadCard branches on 413). The number in the copy is the HONEST ceiling:
     * the deployed Cloud Run service speaks HTTP/1, which caps request bodies at
     * 32MB before Spring's own 50MB limit is ever reached — so we tell veterans
     * 30MB, not 50MB.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(Map.of("detail",
                        "That file is too large to upload — the limit is about 30 MB per file. "
                                + "Try scanning at a lower resolution or splitting the document into parts."));
    }

    /**
     * P1-10 — the monthly usage cap must be wire-DISTINGUISHABLE from the subscription
     * gate. Both used to be 402: the cap here, and {@code subscription_required}
     * (ClaimAccessService) — and the BFF maps ANY 402 to "subscribe", so a PAYING
     * veteran at the cap saw an unactionable upsell/error loop until month reset.
     * The cap is a rate/quota condition, not a payment one → 429 TOO_MANY_REQUESTS
     * with {@code code=USAGE_LIMIT_REACHED} and {@code resumesAt} (ISO-8601 instant,
     * the period end when the ledger resets) so the client can say "chat resumes
     * <date>". {@code periodEnd} is retained for any reader of the old body shape.
     */
    @ExceptionHandler(UsageLimitException.class)
    public ResponseEntity<Map<String, Object>> handleUsageLimit(UsageLimitException ex) {
        String resumesAt = ex.getPeriodEnd().toString();
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of(
                        "code", "USAGE_LIMIT_REACHED",
                        "resumesAt", resumesAt,
                        "periodEnd", resumesAt
                ));
    }

    /**
     * Renders {@link ApiException} as the frozen wire shape
     * {@code {"detail": "<message>"}} (passwordless-otp-auth-spec §2). The status
     * comes from the exception so the passwordless-OTP endpoints can return the
     * exact 400/409/429/502 codes the spec freezes, with user-facing copy already
     * baked into the {@code detail}.
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(Map.of("detail", ex.getMessage()));
    }

    /**
     * Renders {@link StepUpRequiredException} as the pinned step-up contract
     * (auth program P1.2): {@code 403 {code:"step_up_required",
     * acceptedFactors:["otp"]}}. The client's ApiClient interceptor branches on
     * {@code code} to run the step-up ceremony and retry once — the exact seam
     * the 401-retry lives at today.
     */
    @ExceptionHandler(StepUpRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleStepUpRequired(StepUpRequiredException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of(
                        "code", StepUpRequiredException.CODE,
                        "acceptedFactors", ex.getAcceptedFactors()
                ));
    }

    /**
     * Renders {@link RecoveryNeedsSupportException} as the pinned recovery
     * contract (auth program P1.5): {@code 409 {code:"recovery_needs_support"}}.
     * A single-channel legacy account cannot satisfy the dual-channel recovery
     * ceremony (a fresh email code AND a fresh phone proof), so the client
     * branches on {@code code} to route the veteran to the support-hold path.
     */
    @ExceptionHandler(RecoveryNeedsSupportException.class)
    public ResponseEntity<Map<String, Object>> handleRecoveryNeedsSupport(RecoveryNeedsSupportException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", RecoveryNeedsSupportException.CODE));
    }
}
