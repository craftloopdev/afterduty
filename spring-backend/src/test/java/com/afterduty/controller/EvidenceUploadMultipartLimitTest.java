package com.afterduty.controller;

import com.afterduty.service.FakeGcsStorageTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
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
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestTemplate;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-2 — production ran on Spring Boot's 1MB multipart default (no
 * spring.servlet.multipart config anywhere), so every phone photo of a DD-214
 * (2–8MB) was rejected before IntakeController's in-code 50MB check could run.
 * This boots the REAL servlet container (RANDOM_PORT — MockMvc bypasses
 * container multipart parsing, which is exactly where the bug lived) with the
 * production application.yml limits and proves a >1MB upload now succeeds.
 * The oversize→413 path is covered by {@link EvidenceUploadOversize413Test}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Import(FakeGcsStorageTestConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:multipartlimittest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.cloud.gcp.sql.enabled=false",
        "spring.autoconfigure.exclude=com.google.cloud.spring.autoconfigure.sql.GcpCloudSqlAutoConfiguration,com.google.cloud.spring.autoconfigure.firestore.GcpFirestoreAutoConfiguration,com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration,com.google.cloud.spring.autoconfigure.secretmanager.GcpSecretManagerAutoConfiguration"
})
class EvidenceUploadMultipartLimitTest {

    @Autowired
    Environment env;

    @Autowired
    MultipartProperties multipartProperties;

    RestTemplate rest;
    String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + env.getProperty("local.server.port");
        rest = new RestTemplate();
        // Never throw on 4xx/5xx — the tests assert on the status code.
        rest.setErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Email", "multipart-limit@example.com");
        return headers;
    }

    @Test
    void multipartLimits_bindToProductionValues() {
        // 50MB matches the in-code check; Cloud Run's HTTP/1 32MB request cap is
        // the real-world ceiling (the 413 copy says ~30MB for that reason) — these
        // values exist so Spring's limit is never the binding one.
        assertThat(multipartProperties.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(50));
        assertThat(multipartProperties.getMaxRequestSize()).isEqualTo(DataSize.ofMegabytes(55));
    }

    @Test
    void multipartUploadOver1MB_succeeds() {
        // Auto-create the user + claim.
        ResponseEntity<String> claimResp = rest.exchange(
                baseUrl + "/api/claim", HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        assertThat(claimResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 3MB — a typical phone photo, and 3× the old effective cap.
        byte[] bytes = new byte[3 * 1024 * 1024];
        new Random(42).nextBytes(bytes);
        ByteArrayResource file = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "dd214-photo.pdf";
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file);
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> resp = rest.postForEntity(
                baseUrl + "/api/claim/evidence", new HttpEntity<>(body, headers), String.class);

        assertThat(resp.getStatusCode())
                .as("a >1MB multipart upload must pass the container's multipart limits")
                .isEqualTo(HttpStatus.CREATED);
    }
}
