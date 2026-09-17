package com.afterduty.controller;

import com.afterduty.service.FakeGcsStorageTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-2 — an over-limit multipart upload must yield 413 with a human message
 * (UploadCard branches on 413; before the fix this was a generic error and an
 * infinite retry loop). Runs against the REAL container (the limit is enforced
 * by Tomcat's multipart parsing, which MockMvc bypasses) with max-file-size
 * lowered to 2MB so the test trips the exact same MaxUploadSizeExceededException
 * path as a >50MB file in production, without shuttling 50MB through the suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Import(FakeGcsStorageTestConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:oversize413test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.cloud.gcp.sql.enabled=false",
        "spring.autoconfigure.exclude=com.google.cloud.spring.autoconfigure.sql.GcpCloudSqlAutoConfiguration,com.google.cloud.spring.autoconfigure.firestore.GcpFirestoreAutoConfiguration,com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration,com.google.cloud.spring.autoconfigure.secretmanager.GcpSecretManagerAutoConfiguration",
        // 3MB body against a 2MB cap == 51MB body against the production 50MB cap.
        "spring.servlet.multipart.max-file-size=2MB",
        // Let Tomcat swallow the rest of the aborted body so the 413 is readable
        // by this in-process client instead of a connection reset. (In production
        // Cloud Run's HTTP/1 32MB cap 413s oversized bodies at the edge anyway.)
        "server.tomcat.max-swallow-size=-1"
})
class EvidenceUploadOversize413Test {

    @Autowired
    Environment env;

    RestTemplate rest;
    String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + env.getProperty("local.server.port");
        rest = new RestTemplate();
        // Never throw on 4xx/5xx — the test asserts on the status code.
        rest.setErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Email", "oversize-413@example.com");
        return headers;
    }

    @Test
    void oversizeUpload_returns413WithHumanMessage() {
        ResponseEntity<String> claimResp = rest.exchange(
                baseUrl + "/api/claim", HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        assertThat(claimResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        byte[] bytes = new byte[3 * 1024 * 1024];
        new Random(7).nextBytes(bytes);
        ByteArrayResource file = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "huge-scan.pdf";
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file);
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> resp = rest.postForEntity(
                baseUrl + "/api/claim/evidence", new HttpEntity<>(body, headers), String.class);

        assertThat(resp.getStatusCode().value())
                .as("an over-limit upload must be 413, not a generic failure")
                .isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
        // The human message states the HONEST ceiling — Cloud Run's HTTP/1 cap
        // (~30MB usable), not Spring's 50MB — in the frozen {"detail": ...} shape.
        assertThat(resp.getBody()).contains("detail");
        assertThat(resp.getBody()).contains("too large");
        assertThat(resp.getBody()).contains("30 MB");
    }
}
