package com.afterduty.controller;

import com.afterduty.model.Notification;
import com.afterduty.model.User;
import com.afterduty.repository.NotificationRepository;
import com.afterduty.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase B item B1 — pinned contract for GET /api/notifications and
 * POST /api/notifications/mark-read. The critical property is SCOPING: every
 * read/write derives the user from the authenticated principal, so user A's
 * rows are invisible and immutable to user B no matter what ids B submits.
 */
@SpringBootTest
@ActiveProfiles("local")
@Tag("regression")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:notifctrltest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.cloud.gcp.sql.enabled=false",
        "spring.autoconfigure.exclude=com.google.cloud.spring.autoconfigure.sql.GcpCloudSqlAutoConfiguration,com.google.cloud.spring.autoconfigure.firestore.GcpFirestoreAutoConfiguration,com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration,com.google.cloud.spring.autoconfigure.secretmanager.GcpSecretManagerAutoConfiguration"
})
class NotificationControllerTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    OncePerRequestFilter authFilter;

    @Autowired
    UserRepository userRepository;

    @Autowired
    NotificationRepository notificationRepository;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(authFilter)
                .build();
        notificationRepository.deleteAll();
    }

    /** Auto-creates the user via the API and returns it. */
    private User userFor(String email) throws Exception {
        mvc.perform(get("/api/claim").header("X-User-Email", email))
                .andExpect(status().isOk());
        return userRepository.findByEmail(email.toLowerCase())
                .or(() -> userRepository.findByEmail(email))
                .orElseThrow();
    }

    private Notification seed(Long userId, String eventType, String title, Instant createdAt,
                              boolean read, String metadataJson) {
        return notificationRepository.save(Notification.builder()
                .userId(userId)
                .claimId(99L)
                .eventType(eventType)
                .title(title)
                .body(title + " body")
                .severity("info")
                .conditionId(5L)
                .metadataJson(metadataJson)
                .isRead(read)
                .createdAt(createdAt)
                .build());
    }

    // -------------------------------------------------------------------------
    // GET /api/notifications
    // -------------------------------------------------------------------------

    @Test
    void list_returnsPinnedShape_orderedCreatedAtDesc_metadataParsedOrNull() throws Exception {
        User a = userFor("notif-list@example.com");
        // Millis precision: H2/Hibernate store microseconds, so a raw nano-precision
        // Instant would read back truncated and fail the createdAt echo assertion.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        seed(a.getId(), "analysis_complete", "Oldest", now.minus(2, ChronoUnit.HOURS), true, null);
        seed(a.getId(), "rating_changed", "Middle", now.minus(1, ChronoUnit.HOURS), false, "{not json");
        seed(a.getId(), "analysis_updated", "Newest", now, false, "{\"runId\":\"r1\",\"gapsClosed\":1}");

        mvc.perform(get("/api/notifications").header("X-User-Email", "notif-list@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications", hasSize(3)))
                .andExpect(jsonPath("$.unreadCount", is(2)))
                // createdAt DESC
                .andExpect(jsonPath("$.notifications[0].title", is("Newest")))
                .andExpect(jsonPath("$.notifications[1].title", is("Middle")))
                .andExpect(jsonPath("$.notifications[2].title", is("Oldest")))
                // full row shape
                .andExpect(jsonPath("$.notifications[0].eventType", is("analysis_updated")))
                .andExpect(jsonPath("$.notifications[0].claimId", is(99)))
                .andExpect(jsonPath("$.notifications[0].conditionId", is(5)))
                .andExpect(jsonPath("$.notifications[0].severity", is("info")))
                .andExpect(jsonPath("$.notifications[0].body", is("Newest body")))
                .andExpect(jsonPath("$.notifications[0].isRead", is(false)))
                .andExpect(jsonPath("$.notifications[0].createdAt", is(now.toString())))
                // metadata parsed from metadataJson
                .andExpect(jsonPath("$.notifications[0].metadata.runId", is("r1")))
                .andExpect(jsonPath("$.notifications[0].metadata.gapsClosed", is(1)))
                // unparseable and absent metadata resolve to null — omitted from the
                // payload by the app-wide non_null Jackson inclusion — never a 500
                .andExpect(jsonPath("$.notifications[1].metadata").doesNotExist())
                .andExpect(jsonPath("$.notifications[2].metadata").doesNotExist())
                .andExpect(jsonPath("$.notifications[2].isRead", is(true)));
    }

    @Test
    void list_unreadOnlyAndLimit() throws Exception {
        User a = userFor("notif-unread@example.com");
        Instant now = Instant.now();
        seed(a.getId(), "analysis_updated", "ReadRow", now, true, null);
        seed(a.getId(), "rating_changed", "UnreadOld", now.minus(1, ChronoUnit.HOURS), false, null);
        seed(a.getId(), "rating_changed", "UnreadNew", now.minus(1, ChronoUnit.MINUTES), false, null);

        mvc.perform(get("/api/notifications")
                        .param("unreadOnly", "true").param("limit", "1")
                        .header("X-User-Email", "notif-unread@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications", hasSize(1)))
                .andExpect(jsonPath("$.notifications[0].title", is("UnreadNew")))
                .andExpect(jsonPath("$.unreadCount", is(2)));
    }

    @Test
    void list_limitIsClampedTo200() throws Exception {
        userFor("notif-clamp@example.com");
        // A hostile/buggy limit must not blow up the query — it is clamped, not rejected.
        mvc.perform(get("/api/notifications").param("limit", "100000")
                        .header("X-User-Email", "notif-clamp@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications", hasSize(0)))
                .andExpect(jsonPath("$.unreadCount", is(0)));
    }

    // -------------------------------------------------------------------------
    // Scoping — user A's rows are invisible and immutable to user B
    // -------------------------------------------------------------------------

    @Test
    void scoping_userBSeesNothingAndCannotMarkUserAsRows() throws Exception {
        User a = userFor("notif-owner@example.com");
        userFor("notif-intruder@example.com");
        Notification row = seed(a.getId(), "analysis_updated", "Private", Instant.now(), false, null);

        // B reads: empty.
        mvc.perform(get("/api/notifications").header("X-User-Email", "notif-intruder@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications", hasSize(0)))
                .andExpect(jsonPath("$.unreadCount", is(0)));

        // B submits A's real id: nothing updates.
        mvc.perform(post("/api/notifications/mark-read")
                        .header("X-User-Email", "notif-intruder@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\": [" + row.getId() + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated", is(0)));

        // B's "all" sweep can't touch A either.
        mvc.perform(post("/api/notifications/mark-read")
                        .header("X-User-Email", "notif-intruder@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"all\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated", is(0)));

        // A's row is still unread.
        assertFalse(notificationRepository.findById(row.getId()).orElseThrow().getIsRead());
    }

    // -------------------------------------------------------------------------
    // POST /api/notifications/mark-read
    // -------------------------------------------------------------------------

    @Test
    void markRead_byIds_isIdempotent() throws Exception {
        User a = userFor("notif-mark@example.com");
        Notification n1 = seed(a.getId(), "rating_changed", "One", Instant.now(), false, null);
        Notification n2 = seed(a.getId(), "rating_changed", "Two", Instant.now(), false, null);

        mvc.perform(post("/api/notifications/mark-read")
                        .header("X-User-Email", "notif-mark@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\": [" + n1.getId() + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated", is(1)));

        // Re-marking the same id updates nothing more.
        mvc.perform(post("/api/notifications/mark-read")
                        .header("X-User-Email", "notif-mark@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\": [" + n1.getId() + ", " + n2.getId() + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated", is(1)));

        mvc.perform(get("/api/notifications").header("X-User-Email", "notif-mark@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount", is(0)));
    }

    @Test
    void markRead_all() throws Exception {
        User a = userFor("notif-all@example.com");
        seed(a.getId(), "rating_changed", "One", Instant.now(), false, null);
        seed(a.getId(), "rating_changed", "Two", Instant.now(), false, null);
        seed(a.getId(), "rating_changed", "Already", Instant.now(), true, null);

        mvc.perform(post("/api/notifications/mark-read")
                        .header("X-User-Email", "notif-all@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"all\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated", is(2)));

        mvc.perform(get("/api/notifications").header("X-User-Email", "notif-all@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount", is(0)));
    }

    @Test
    void markRead_neitherIdsNorAll_is400() throws Exception {
        userFor("notif-bad@example.com");
        mvc.perform(post("/api/notifications/mark-read")
                        .header("X-User-Email", "notif-bad@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
