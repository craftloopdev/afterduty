package com.afterduty.service;

import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * {@link EmailSender} backed by SendGrid's official {@code sendgrid-java} client
 * (passwordless-otp-auth-spec §5.1 / §5.4). Mirrors VolunTails'
 * {@code notification_service.send_email}:
 *
 * <ul>
 *   <li><b>Graceful-degrade:</b> when {@code va-claim.sendgrid.api-key} is empty
 *       (local / unconfigured) the send returns {@link Status#SKIPPED} — the mint
 *       path tolerates that only under {@code dev-mode} (the {@code 123456} dev
 *       shortcut covers local testing); otherwise it 502s.</li>
 *   <li><b>No PII trail:</b> never logs the recipient's body (it carries the
 *       one-time code) and never resolves a {@code User} (no timing oracle).</li>
 * </ul>
 */
@Service
public class SendGridEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SendGridEmailSender.class);

    private final String apiKey;
    private final String fromEmail;

    public SendGridEmailSender(
            @Value("${va-claim.sendgrid.api-key:}") String apiKey,
            @Value("${va-claim.sendgrid.from-email:noreply@afterduty.app}") String fromEmail) {
        this.apiKey = apiKey;
        this.fromEmail = fromEmail;
    }

    @Override
    public Result sendHtml(String toEmail, String subject, String htmlBody) {
        if (apiKey == null || apiKey.isBlank()) {
            // §5.4: unconfigured ⇒ SKIPPED (not an error). The caller decides
            // whether SKIPPED is tolerable (dev grace) or a 502 (prod).
            log.warn("SendGrid not configured (va-claim.sendgrid.api-key empty); "
                    + "email NOT sent. This is expected only in local/dev.");
            return Result.skipped();
        }

        Mail mail = new Mail(
                new Email(fromEmail),
                subject,
                new Email(toEmail),
                new Content("text/html", htmlBody));

        try {
            SendGrid client = new SendGrid(apiKey);
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());
            Response response = client.api(request);

            int status = response.getStatusCode();
            if (status >= 200 && status < 300) {
                // X-Message-Id is the provider's external id (best-effort).
                String messageId = response.getHeaders() != null
                        ? response.getHeaders().get("X-Message-Id") : null;
                return Result.sent(messageId);
            }
            // Non-2xx: log status only (the SendGrid error body can echo the
            // recipient; keep it terse and never include htmlBody).
            log.error("SendGrid send failed: HTTP {}", status);
            return Result.failed("email service returned HTTP " + status);
        } catch (IOException e) {
            // Log the exception MESSAGE only — never the body (carries the code).
            log.error("SendGrid send threw: {}", e.getMessage());
            return Result.failed("email service unavailable");
        }
    }
}
