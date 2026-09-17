package com.afterduty.service;

import com.afterduty.model.AuthAuditLog;
import com.afterduty.repository.AuthAuditLogRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Append + no-secrets/PHI tests for {@link AuthAuditService} (auth program P1.1).
 *
 * <p>Runs against the H2 (MODE=PostgreSQL) slice DB with the real repo + entity.
 * Proves: an event is appended; the {@code detail_json} builder accepts ONLY the
 * allowlisted keys (a caller cannot smuggle a code/token/secret/PHI); and the
 * repository has no update/delete surface.
 */
@DataJpaTest
@Import(AuthAuditService.class)
@Tag("regression")
class AuthAuditServiceTest {

    @Autowired
    AuthAuditService service;

    @Autowired
    AuthAuditLogRepository repo;

    // ---- append -------------------------------------------------------------

    @Test
    void recordAppendsRowWithIpAndUserAgentFromRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "1.1.1.1, 203.0.113.7, 35.191.0.5");
        req.addHeader("User-Agent", "vcp-test/1.0");

        service.record(AuthAuditLog.EVENT_SIGN_IN, AuthAuditLog.CHANNEL_EMAIL,
                AuthAuditLog.OUTCOME_SUCCESS, 42L, req,
                AuthAuditService.detail().credentialType("otp").purpose("signin"));

        List<AuthAuditLog> rows = repo.findByUserIdOrderByCreatedAtDesc(42L);
        assertThat(rows).hasSize(1);
        AuthAuditLog row = rows.get(0);
        assertThat(row.getEventType()).isEqualTo(AuthAuditLog.EVENT_SIGN_IN);
        assertThat(row.getChannel()).isEqualTo("email");
        assertThat(row.getOutcome()).isEqualTo("SUCCESS");
        assertThat(row.getUserId()).isEqualTo(42L);
        // Second-to-last XFF entry is the trusted client IP (never the leftmost).
        assertThat(row.getIp()).isEqualTo("203.0.113.7");
        assertThat(row.getUserAgent()).isEqualTo("vcp-test/1.0");
        assertThat(row.getCreatedAt()).isNotNull();
        // Only allowlisted keys present.
        assertThat(row.getDetailJson()).contains("credentialType").contains("purpose");
    }

    @Test
    void recordAllowsNullUserForPreAuthEvents() {
        // NOTE: record() runs in a REQUIRES_NEW transaction (an audit write must
        // survive a caller rollback), so committed rows persist across tests in
        // the shared in-memory DB — assert on THIS event's rows only, never on a
        // global count. Use a distinctive channel to isolate this test's writes.
        service.record(AuthAuditLog.EVENT_OTP_REQUESTED, "biometric",
                AuthAuditLog.OUTCOME_SUCCESS, null, new MockHttpServletRequest());
        assertThat(repo.findByEventTypeOrderByCreatedAtDesc(AuthAuditLog.EVENT_OTP_REQUESTED))
                .isNotEmpty()
                .filteredOn(r -> "biometric".equals(r.getChannel()))
                .hasSize(1)
                .allSatisfy(r -> assertThat(r.getUserId()).isNull());
    }

    // ---- no secrets / PHI in detail_json ------------------------------------

    @Test
    void detailBuilderOnlyAcceptsAllowlistedKeys() {
        // The exact set the P1.1 rule mandates — reason/purpose/credentialType/factor/mfaModel.
        assertThat(AuthAuditService.ALLOWED_DETAIL_KEYS)
                .containsExactlyInAnyOrder("reason", "purpose", "credentialType", "factor", "mfaModel");
    }

    @Test
    void detailBuilderHasNoFreeFormPutEscapeHatch() {
        // There is no PUBLIC map/put method on DetailBuilder; the only ways to add
        // context are the five typed setters. A blank/null value is dropped.
        service.record(AuthAuditLog.EVENT_STEP_UP_VERIFIED, "phone",
                AuthAuditLog.OUTCOME_SUCCESS, 7L, new MockHttpServletRequest(),
                AuthAuditService.detail().reason("").purpose(null));
        // The dropped blank/null values leave detail_json null (nothing to store).
        assertThat(repo.findByUserIdOrderByCreatedAtDesc(7L).get(0).getDetailJson()).isNull();

        // Compile/runtime assertion: DetailBuilder exposes no public put(String,String).
        var methods = AuthAuditService.DetailBuilder.class.getMethods();
        assertThat(methods)
                .noneMatch(m -> m.getName().equals("put") && java.lang.reflect.Modifier.isPublic(m.getModifiers()));
    }

    @Test
    void detailPutRejectsNonAllowlistedKeyReflectively() throws Exception {
        // Force the private put() with an off-list key: it MUST throw, so even a
        // future accidental caller cannot land a non-allowlisted key.
        AuthAuditService.DetailBuilder b = AuthAuditService.detail();
        var put = AuthAuditService.DetailBuilder.class
                .getDeclaredMethod("put", String.class, String.class);
        put.setAccessible(true);
        assertThatThrownBy(() -> {
            try {
                put.invoke(b, "code", "123456");
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowlisted");
    }

    @Test
    void detailJsonNeverContainsAValueThatWasNotSet() {
        // Even a code-shaped string is only stored if placed under an allowlisted
        // key. reason() is allowlisted, but the point of the test is that the
        // builder physically has no way to key it as "code"/"token"/"otp".
        service.record(AuthAuditLog.EVENT_OTP_FAILED, AuthAuditLog.CHANNEL_EMAIL,
                AuthAuditLog.OUTCOME_FAILURE, 1L, new MockHttpServletRequest(),
                AuthAuditService.detail().reason("bad_code"));
        String json = repo.findByUserIdOrderByCreatedAtDesc(1L).get(0).getDetailJson();
        assertThat(json).isEqualTo("{\"reason\":\"bad_code\"}");
        assertThat(json).doesNotContain("code\":\"1")   // no OTP-looking value smuggled
                .doesNotContain("token");
    }

    // ---- append-only repository surface -------------------------------------

    @Test
    void repositoryExposesNoDeleteOrUpdateMethods() {
        var methods = AuthAuditLogRepository.class.getMethods();
        assertThat(methods)
                .noneMatch(m -> m.getName().startsWith("delete"))
                .noneMatch(m -> m.getName().equals("deleteAll"))
                .noneMatch(m -> m.getName().equals("saveAndFlush"));
        // save + reads only.
        assertThat(methods).anyMatch(m -> m.getName().equals("save"));
    }
}
