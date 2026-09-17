package com.afterduty.controller;

import com.afterduty.model.EvidenceItem;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.EvidenceRepository;
import com.afterduty.repository.UserRepository;
import com.afterduty.service.FakeGcsStorageTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.OncePerRequestFilter;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Storage-truth integration tests for the evidence upload path: a multipart
 * file upload must land the bytes in GCS (the in-memory {@link FakeGcsStorageTestConfig})
 * and leave the DB row with a {@code gcs_path} and NO file bytes in
 * {@code raw_content}; the download endpoint must serve those bytes from GCS
 * with the original media type.
 */
@SpringBootTest
@ActiveProfiles("local")
@Tag("regression")
@Import(FakeGcsStorageTestConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:storagetruthtest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.cloud.gcp.sql.enabled=false",
        "spring.autoconfigure.exclude=com.google.cloud.spring.autoconfigure.sql.GcpCloudSqlAutoConfiguration,com.google.cloud.spring.autoconfigure.firestore.GcpFirestoreAutoConfiguration,com.google.cloud.spring.autoconfigure.storage.GcpStorageAutoConfiguration,com.google.cloud.spring.autoconfigure.secretmanager.GcpSecretManagerAutoConfiguration"
})
class IntakeControllerStorageTruthTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    OncePerRequestFilter authFilter;

    @Autowired
    ClaimRepository claimRepository;

    @Autowired
    EvidenceRepository evidenceRepository;

    @Autowired
    UserRepository userRepository;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(authFilter)
                .build();
    }

    @Test
    void multipartUpload_storesBytesInGcs_rowHasGcsPathAndNoFileBytesInRawContent() throws Exception {
        String email = "storage-truth@example.com";
        // Auto-create the claim.
        mvc.perform(get("/api/claim").header("X-User-Email", email))
                .andExpect(status().isOk());
        Long userId = userRepository.findByEmail(email.toLowerCase())
                .or(() -> userRepository.findByEmail(email))
                .orElseThrow().getId();
        long claimId = claimRepository.findByUserIdOrderByCreatedAtDesc(userId).get(0).getId();

        byte[] pdfBytes = "%PDF-1.7 fake body".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "evidence.pdf", "application/pdf", pdfBytes);

        mvc.perform(multipart("/api/claim/evidence")
                        .file(file)
                        .header("X-User-Email", email))
                .andExpect(status().isCreated());

        List<EvidenceItem> rows = evidenceRepository.findByClaimIdOrderByCreatedAt(claimId);
        assertThat(rows).hasSize(1);
        EvidenceItem row = rows.get(0);

        // gcs_path set, content-addressed under the file hash.
        assertThat(row.getGcsPath()).isNotNull();
        assertThat(row.getGcsPath()).startsWith("claims/" + claimId + "/evidence/");
        // NO file bytes parked in raw_content.
        assertThat(row.getRawContent()).isNull();
        // hash + size + media type captured.
        assertThat(row.getFileHash()).isNotBlank();
        assertThat(row.getFileSize()).isEqualTo((long) pdfBytes.length);
        assertThat(row.getMediaType()).isEqualTo("application/pdf");

        // Download endpoint serves the original bytes from GCS with the media type.
        mvc.perform(get("/api/claim/evidence/" + row.getId() + "/download")
                        .header("X-User-Email", email))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/pdf"))
                .andExpect(content().bytes(pdfBytes));
    }
}
