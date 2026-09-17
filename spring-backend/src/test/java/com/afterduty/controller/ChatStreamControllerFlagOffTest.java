package com.afterduty.controller;

import com.afterduty.service.ChatAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Increment 7 §F.2 — streaming rollback lever. With {@code va-claim.chat.streaming=false}
 * the SSE endpoint returns 409 {@code streaming_disabled} so the web client falls back to
 * the legacy non-streaming POST (§F.4). The flag is read at bean init, hence a dedicated
 * context.
 */
@SpringBootTest
@ActiveProfiles("local")
@Tag("regression")
@TestPropertySource(properties = {
        "va-claim.chat.streaming=false",
        "spring.datasource.url=jdbc:h2:mem:chatstreamoff;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.cloud.gcp.sql.enabled=false",
        "spring.autoconfigure.exclude=com.google.cloud.spring.autoconfigure.sql.GcpCloudSqlAutoConfiguration,com.google.cloud.spring.autoconfigure.firestore.GcpFirestoreAutoConfiguration,com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration,com.google.cloud.spring.autoconfigure.secretmanager.GcpSecretManagerAutoConfiguration"
})
class ChatStreamControllerFlagOffTest {

    @Autowired WebApplicationContext context;
    @Autowired OncePerRequestFilter authFilter;
    @MockitoBean ChatAgent chatAgent;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(authFilter).build();
    }

    @Test
    void streamingDisabled_returns409() throws Exception {
        mvc.perform(post("/api/claim/chat/stream")
                        .header("X-User-Email", "off-" + System.nanoTime() + "@test.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isConflict());   // 409 streaming_disabled, before any emitter
    }
}
