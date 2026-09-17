package com.afterduty.service;

import com.afterduty.exception.ApiException;
import com.afterduty.model.EmailVerificationCode;
import com.afterduty.repository.EmailVerificationCodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Port-fidelity + security tests for {@link EmailCodeService} — the mint+send and
 * verify+consume core ported from VolunTails (passwordless-otp-auth-spec §3, §11).
 *
 * <p>Runs against the H2 ({@code MODE=PostgreSQL}) slice DB with the real repo and
 * entity. {@link EmailSender} is mocked so we assert on what is (and is NOT) sent
 * without touching SendGrid. The service runs with dev-mode OFF and a non-empty
 * SendGrid key so the dev {@code 123456} shortcut and the dev-unconfigured grace
 * are inert (the secure prod-like defaults); one test flips dev-mode on to cover
 * the shortcut explicitly.
 */
@DataJpaTest
@Import(EmailCodeService.class)
@Tag("regression")
class EmailCodeServiceTest {

    private static final String EMAIL = "vet@example.com";
    private static final String SIGNIN = EmailVerificationCode.PURPOSE_SIGNIN;
    private static final String ATTACH = EmailVerificationCode.PURPOSE_ATTACH;
    private static final String SUBJECT = "Your After Duty sign-in code";

    @MockitoBean
    EmailSender emailSender;

    @Autowired
    EmailCodeService service;

    @Autowired
    EmailVerificationCodeRepository repo;

    @BeforeEach
    void setUp() {
        // Prod-like secure defaults: dev shortcut + dev grace inert.
        ReflectionTestUtils.setField(service, "devMode", false);
        ReflectionTestUtils.setField(service, "sendGridApiKey", "SG.configured-key");
        when(emailSender.sendHtml(anyString(), anyString(), anyString()))
                .thenReturn(EmailSender.Result.sent("msg-1"));
    }

    // ---- mint + send ---------------------------------------------------------

    @Test
    void mintStoresHashedCodeAndSendsExactlyOnce() {
        service.mintAndSend(EMAIL, "1.2.3.4", SUBJECT, SIGNIN, false);

        List<EmailVerificationCode> rows = repo.findAll();
        assertThat(rows).hasSize(1);
        EmailVerificationCode row = rows.get(0);
        // 64 hex chars = SHA-256; NOT a 6-digit plaintext code.
        assertThat(row.getCodeHash()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(row.getCodeHash()).doesNotMatch("\\d{6}");
        assertThat(row.getEmail()).isEqualTo(EMAIL);
        assertThat(row.getPurpose()).isEqualTo(SIGNIN);
        assertThat(row.getRequestIp()).isEqualTo("1.2.3.4");
        assertThat(row.getConsumedAt()).isNull();
        // 10-min TTL.
        assertThat(Duration.between(row.getCreatedAt(), row.getExpiresAt()).toMinutes()).isEqualTo(10);

        verify(emailSender).sendHtml(eq(EMAIL), eq(SUBJECT), anyString());
    }

    @Test
    void codeIsNeverLoggedOrReturnedAndBodyIsTheOnlyPlaceWithThePlaintext() {
        // The send body is the ONLY carrier of the plaintext code; capture it and
        // confirm the stored row is the hash of exactly that code (proves the code
        // never leaks via the row, and binds the hash to the sent code).
        var bodyCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        service.mintAndSend(EMAIL, "1.2.3.4", SUBJECT, SIGNIN, false);
        verify(emailSender).sendHtml(eq(EMAIL), eq(SUBJECT), bodyCaptor.capture());

        String body = bodyCaptor.getValue();
        String code = extractDigits(body);
        assertThat(code).hasSize(6);
        EmailVerificationCode row = repo.findAll().get(0);
        assertThat(row.getCodeHash()).isEqualTo(sha256Hex(code));
    }

    @Test
    void resendWithinCooldownIs429() {
        service.mintAndSend(EMAIL, null, SUBJECT, SIGNIN, false);
        assertApi(() -> service.mintAndSend(EMAIL, null, SUBJECT, SIGNIN, false),
                HttpStatus.TOO_MANY_REQUESTS, "Please wait a moment");
        // Only the first send happened; the cooldown-rejected one sent nothing.
        verify(emailSender).sendHtml(anyString(), anyString(), anyString());
    }

    @Test
    void perEmailHourlyCapIs429() {
        // 5 codes are allowed; back-date them so the cooldown doesn't fire first.
        seedRows(EMAIL, "9.9.9.9", 5, SIGNIN, Duration.ofMinutes(2));
        assertApi(() -> service.mintAndSend(EMAIL, "9.9.9.9", SUBJECT, SIGNIN, false),
                HttpStatus.TOO_MANY_REQUESTS, "Too many codes requested");
        verify(emailSender, never()).sendHtml(anyString(), anyString(), anyString());
    }

    @Test
    void perIpHourlyCapIs429AcrossDifferentEmails() {
        // 20 codes from one IP spread across many emails (defeats per-email cap).
        for (int i = 0; i < 20; i++) {
            seedRows("u" + i + "@example.com", "5.5.5.5", 1, SIGNIN, Duration.ofMinutes(2));
        }
        assertApi(() -> service.mintAndSend("fresh@example.com", "5.5.5.5", SUBJECT, SIGNIN, false),
                HttpStatus.TOO_MANY_REQUESTS, "Too many codes requested");
        verify(emailSender, never()).sendHtml(anyString(), anyString(), anyString());
    }

    @Test
    void mintBurnsPriorLiveCodesSoOnlyOneIsLive() {
        // Two sequential mints (back-date the first past the cooldown).
        seedRows(EMAIL, null, 1, SIGNIN, Duration.ofMinutes(1));
        service.mintAndSend(EMAIL, null, SUBJECT, SIGNIN, false);

        long live = repo.findAll().stream().filter(r -> r.getConsumedAt() == null).count();
        assertThat(live).isEqualTo(1);
    }

    @Test
    void sendFailureIs502WhenConfigured() {
        when(emailSender.sendHtml(anyString(), anyString(), anyString()))
                .thenReturn(EmailSender.Result.failed("email service returned HTTP 500"));
        assertApi(() -> service.mintAndSend(EMAIL, null, SUBJECT, SIGNIN, false),
                HttpStatus.BAD_GATEWAY, "Email could not be sent");
    }

    @Test
    void skippedSendToleratedUnderDevUnconfiguredGrace() {
        ReflectionTestUtils.setField(service, "devMode", true);
        ReflectionTestUtils.setField(service, "sendGridApiKey", "");
        when(emailSender.sendHtml(anyString(), anyString(), anyString()))
                .thenReturn(EmailSender.Result.skipped());
        assertDoesNotThrow(() -> service.mintAndSend(EMAIL, null, SUBJECT, SIGNIN, false));
    }

    // ---- verify + consume ----------------------------------------------------

    @Test
    void verifyHappyPathConsumesTheCode() {
        String code = mintAndCapture(SIGNIN);
        assertDoesNotThrow(() -> service.verifyAndConsume(EMAIL, code, SIGNIN));
        EmailVerificationCode row = repo.findAll().get(0);
        assertThat(row.getConsumedAt()).isNotNull();   // consumed (single-use)
    }

    @Test
    void verifyWrongCodeIncrementsAttemptsAndIsGeneric400() {
        mintAndCapture(SIGNIN);
        assertApi(() -> service.verifyAndConsume(EMAIL, "000000", SIGNIN),
                HttpStatus.BAD_REQUEST, "Incorrect or expired code");
        assertThat(repo.findAll().get(0).getAttempts()).isEqualTo(1);
        assertThat(repo.findAll().get(0).getConsumedAt()).isNull();   // not consumed on a wrong guess
    }

    @Test
    void verifyExpiredCodeIsGeneric400() {
        // Seed an already-expired live row.
        EmailVerificationCode row = new EmailVerificationCode();
        row.setEmail(EMAIL);
        row.setCodeHash(sha256Hex("123456"));
        row.setPurpose(SIGNIN);
        row.setAttempts(0);
        Instant now = Instant.now();
        row.setCreatedAt(now.minus(Duration.ofMinutes(20)));
        row.setExpiresAt(now.minus(Duration.ofMinutes(10)));   // expired 10 min ago
        repo.saveAndFlush(row);

        assertApi(() -> service.verifyAndConsume(EMAIL, "123456", SIGNIN),
                HttpStatus.BAD_REQUEST, "Incorrect or expired code");
    }

    @Test
    void verifyMaxAttemptsExhaustedIsGeneric400() {
        EmailVerificationCode row = new EmailVerificationCode();
        row.setEmail(EMAIL);
        row.setCodeHash(sha256Hex("123456"));
        row.setPurpose(SIGNIN);
        row.setAttempts(EmailCodeService.EMAIL_CODE_MAX_ATTEMPTS);   // already at cap
        Instant now = Instant.now();
        row.setCreatedAt(now);
        row.setExpiresAt(now.plus(Duration.ofMinutes(10)));
        repo.saveAndFlush(row);

        // Even the CORRECT code is rejected once the attempt cap is hit (no oracle).
        assertApi(() -> service.verifyAndConsume(EMAIL, "123456", SIGNIN),
                HttpStatus.BAD_REQUEST, "Incorrect or expired code");
    }

    @Test
    void verifyNoCodeIsGeneric400() {
        assertApi(() -> service.verifyAndConsume("nobody@example.com", "123456", SIGNIN),
                HttpStatus.BAD_REQUEST, "Incorrect or expired code");
    }

    @Test
    void consumedCodeCannotBeReused() {
        String code = mintAndCapture(SIGNIN);
        service.verifyAndConsume(EMAIL, code, SIGNIN);             // first use OK
        assertApi(() -> service.verifyAndConsume(EMAIL, code, SIGNIN),
                HttpStatus.BAD_REQUEST, "Incorrect or expired code");   // second use fails
    }

    @Test
    void signinCodeCannotSatisfyAttachAndViceVersa() {
        String signinCode = mintAndCapture(SIGNIN);
        // A SIGNIN code is invisible to the ATTACH verify lane.
        assertApi(() -> service.verifyAndConsume(EMAIL, signinCode, ATTACH),
                HttpStatus.BAD_REQUEST, "Incorrect or expired code");
        // ...and it is still usable in its own lane (purpose scoping, not a burn).
        assertDoesNotThrow(() -> service.verifyAndConsume(EMAIL, signinCode, SIGNIN));
    }

    @Test
    void devShortcutWorksOnlyWhenDevModeAndSendGridUnconfigured() {
        ReflectionTestUtils.setField(service, "devMode", true);
        ReflectionTestUtils.setField(service, "sendGridApiKey", "");
        when(emailSender.sendHtml(anyString(), anyString(), anyString()))
                .thenReturn(EmailSender.Result.skipped());
        // Mint a real (random) code; 123456 still verifies via the dev shortcut.
        service.mintAndSend(EMAIL, null, SUBJECT, SIGNIN, false);
        assertDoesNotThrow(() -> service.verifyAndConsume(EMAIL, "123456", SIGNIN));
    }

    @Test
    void devShortcutInertWhenSendGridConfigured() {
        // dev-mode on, but a configured key ⇒ the 123456 shortcut must NOT work.
        ReflectionTestUtils.setField(service, "devMode", true);
        ReflectionTestUtils.setField(service, "sendGridApiKey", "SG.configured");
        service.mintAndSend(EMAIL, null, SUBJECT, SIGNIN, false);
        assertApi(() -> service.verifyAndConsume(EMAIL, "123456", SIGNIN),
                HttpStatus.BAD_REQUEST, "Incorrect or expired code");
    }

    // ---- helpers -------------------------------------------------------------

    /** Mint a real code and return the 6-digit plaintext captured from the send body. */
    private String mintAndCapture(String purpose) {
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        service.mintAndSend(EMAIL, null, SUBJECT, purpose, ATTACH.equals(purpose));
        verify(emailSender).sendHtml(eq(EMAIL), anyString(), captor.capture());
        org.mockito.Mockito.clearInvocations(emailSender);
        when(emailSender.sendHtml(anyString(), anyString(), anyString()))
                .thenReturn(EmailSender.Result.sent("msg"));
        return extractDigits(captor.getValue());
    }

    private void seedRows(String email, String ip, int count, String purpose, Duration ageStep) {
        Instant now = Instant.now();
        for (int i = 0; i < count; i++) {
            EmailVerificationCode row = new EmailVerificationCode();
            row.setEmail(email);
            row.setCodeHash(sha256Hex(String.format("%06d", i)));
            row.setPurpose(purpose);
            row.setAttempts(0);
            row.setRequestIp(ip);
            // Stagger ages so none is within the 30s cooldown window.
            Instant created = now.minus(ageStep.multipliedBy(i + 1));
            row.setCreatedAt(created);
            row.setExpiresAt(created.plus(Duration.ofMinutes(10)));
            repo.saveAndFlush(row);
        }
    }

    private static String extractDigits(String body) {
        var m = java.util.regex.Pattern.compile(">(\\d{6})<").matcher(body);
        if (m.find()) return m.group(1);
        // Fallback: first 6-digit run anywhere in the body.
        var m2 = java.util.regex.Pattern.compile("(\\d{6})").matcher(body);
        return m2.find() ? m2.group(1) : "";
    }

    private static void assertApi(org.junit.jupiter.api.function.Executable e,
                                  HttpStatus status, String detailContains) {
        assertThatThrownBy(e::execute)
                .isInstanceOf(ApiException.class)
                .satisfies(t -> {
                    ApiException ae = (ApiException) t;
                    assertThat(ae.getStatus()).isEqualTo(status);
                    assertThat(ae.getMessage()).contains(detailContains);
                });
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
