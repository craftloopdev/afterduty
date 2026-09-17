package com.afterduty.service;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.afterduty.model.EvidenceItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Storage-truth unit tests for {@link DocumentStorageService}: the upload /
 * read / delete round-trip against an in-memory {@link FakeGcsStorage}, plus
 * the legacy-rows fallback (base64-in-raw_content with no gcs_path) that must
 * keep working forever.
 */
@Tag("regression")
class DocumentStorageServiceStorageTruthTest {

    private Storage fakeStorage;
    private DocumentStorageService service;

    @BeforeEach
    void setUp() {
        fakeStorage = FakeGcsStorage.create();
        // EvidenceRepository is only used by isDuplicate(); these tests don't
        // touch that path, so a bare Mockito mock is sufficient.
        service = new DocumentStorageService(
                mock(com.afterduty.repository.EvidenceRepository.class), fakeStorage);
        // @Value-injected bucket is only set by Spring; supply it directly here.
        ReflectionTestUtils.setField(service, "bucket", "vaclaim-documents");
    }

    // -------------------------------------------------------------------------
    // Upload → GCS, then read back
    // -------------------------------------------------------------------------

    @Test
    void uploadToGcs_thenLoadFileBytes_roundTrips() {
        byte[] bytes = "fake-pdf-bytes".getBytes(StandardCharsets.UTF_8);
        String hash = service.computeHash(bytes);
        String path = service.buildEvidenceObjectPath(42L, hash);

        service.uploadToGcs(bytes, path, "application/pdf");

        // A GCS-backed row (gcs_path set, raw_content null) reads back from GCS.
        EvidenceItem gcsRow = EvidenceItem.builder()
                .claimId(42L).sourceType("upload").filename("report.pdf")
                .mediaType("application/pdf").gcsPath(path).rawContent(null)
                .fileHash(hash).fileSize((long) bytes.length).build();

        assertThat(service.loadFileBytes(gcsRow)).isEqualTo(bytes);
    }

    @Test
    void buildEvidenceObjectPath_isContentAddressed() {
        String hash = service.computeHash("x".getBytes(StandardCharsets.UTF_8));
        assertThat(service.buildEvidenceObjectPath(7L, hash))
                .isEqualTo("claims/7/evidence/" + hash);
    }

    // -------------------------------------------------------------------------
    // Reader prefers GCS over raw_content
    // -------------------------------------------------------------------------

    @Test
    void loadFileBytes_prefersGcsOverRawContent() {
        byte[] gcsBytes = "real-gcs-content".getBytes(StandardCharsets.UTF_8);
        String path = service.buildEvidenceObjectPath(1L, "deadbeef");
        service.uploadToGcs(gcsBytes, path, "application/pdf");

        // Row has BOTH a gcs_path and stale raw_content; GCS must win.
        EvidenceItem row = EvidenceItem.builder()
                .claimId(1L).sourceType("upload").gcsPath(path)
                .rawContent("STALE legacy raw content").build();

        assertThat(service.loadFileBytes(row)).isEqualTo(gcsBytes);
    }

    // -------------------------------------------------------------------------
    // Legacy fallback — base64 envelope in raw_content, no gcs_path
    // -------------------------------------------------------------------------

    @Test
    void loadFileBytes_fallsBackToLegacyBase64Envelope() {
        byte[] original = "legacy-binary".getBytes(StandardCharsets.UTF_8);
        String envelope = "CONTENT_ENCODING: base64\nFILE_TYPE: application/pdf\nFILENAME: old.pdf\n\n"
                + Base64.getEncoder().encodeToString(original);

        EvidenceItem legacyRow = EvidenceItem.builder()
                .claimId(99L).sourceType("upload").filename("old.pdf")
                .gcsPath(null).rawContent(envelope).build();

        assertThat(service.loadFileBytes(legacyRow)).isEqualTo(original);
    }

    @Test
    void loadFileBytes_fallsBackToPlainTextRawContent() {
        // Typed / quick-add evidence: raw_content is plain text, not an envelope.
        EvidenceItem textRow = EvidenceItem.builder()
                .claimId(99L).sourceType("quick_add").gcsPath(null)
                .rawContent("PTSD since 2010").build();

        assertThat(new String(service.loadFileBytes(textRow), StandardCharsets.UTF_8))
                .isEqualTo("PTSD since 2010");
    }

    // -------------------------------------------------------------------------
    // Extraction text — byte-identical envelope from GCS, verbatim for text
    // -------------------------------------------------------------------------

    @Test
    void loadExtractionText_gcsRow_reconstructsCanonicalEnvelope() {
        byte[] bytes = "abc-123".getBytes(StandardCharsets.UTF_8);
        String path = service.buildEvidenceObjectPath(5L, "hash5");
        service.uploadToGcs(bytes, path, "application/pdf");

        EvidenceItem gcsRow = EvidenceItem.builder()
                .claimId(5L).sourceType("upload").filename("scan.pdf")
                .mediaType("application/pdf").gcsPath(path).rawContent(null).build();

        String expected = "CONTENT_ENCODING: base64\nFILE_TYPE: application/pdf\nFILENAME: scan.pdf\n\n"
                + Base64.getEncoder().encodeToString(bytes);
        assertThat(service.loadExtractionText(gcsRow)).isEqualTo(expected);
    }

    @Test
    void loadExtractionText_textRow_returnsRawContentVerbatim() {
        EvidenceItem textRow = EvidenceItem.builder()
                .claimId(5L).sourceType("quick_add").gcsPath(null)
                .rawContent("just some typed text").build();
        assertThat(service.loadExtractionText(textRow)).isEqualTo("just some typed text");
    }

    @Test
    void loadExtractionText_legacyBase64Row_returnsEnvelopeVerbatim() {
        // Pre-storage-truth file row: the base64 envelope lives in raw_content and
        // there's no gcs_path. Extraction must see exactly that string, unchanged.
        String envelope = "CONTENT_ENCODING: base64\nFILE_TYPE: image/png\nFILENAME: x.png\n\nQUJD";
        EvidenceItem legacyRow = EvidenceItem.builder()
                .claimId(5L).sourceType("upload").gcsPath(null).rawContent(envelope).build();
        assertThat(service.loadExtractionText(legacyRow)).isEqualTo(envelope);
    }

    // -------------------------------------------------------------------------
    // Delete removes the object
    // -------------------------------------------------------------------------

    @Test
    void deleteFromGcs_removesObject() {
        byte[] bytes = "to-delete".getBytes(StandardCharsets.UTF_8);
        String path = service.buildEvidenceObjectPath(3L, "delhash");
        service.uploadToGcs(bytes, path, "text/plain");

        // present before delete
        EvidenceItem row = EvidenceItem.builder().gcsPath(path).build();
        assertThat(service.loadFileBytes(row)).isEqualTo(bytes);

        boolean deleted = service.deleteFromGcs(path);
        assertThat(deleted).isTrue();

        // gone after delete
        assertThat(service.loadFileBytes(row)).isNull();
        // deleting again is a no-op miss
        assertThat(service.deleteFromGcs(path)).isFalse();
    }

    // -------------------------------------------------------------------------
    // 404 semantics: real GCS throws StorageException(404) on a missing blob.
    // downloadFromGcs translates that single case into the null "miss" contract
    // and rethrows every other StorageException.
    // -------------------------------------------------------------------------

    @Test
    void downloadFromGcs_missingObject_throws404_returnsNull() {
        // The in-memory fake now throws a 404 StorageException for an absent
        // object exactly like real GCS — and downloadFromGcs must surface that
        // as the documented null miss, not propagate the exception.
        assertThat(service.downloadFromGcs("claims/1/evidence/does-not-exist")).isNull();
    }

    @Test
    void downloadFromGcs_nonNotFoundStorageException_rethrows() {
        // A non-404 StorageException (auth/network/5xx) must NOT be swallowed as a
        // miss — it propagates so genuine failures are never mistaken for "absent".
        Storage failing = mock(Storage.class);
        when(failing.readAllBytes(any(BlobId.class)))
                .thenThrow(new StorageException(503, "backendError"));
        DocumentStorageService svc = new DocumentStorageService(
                mock(com.afterduty.repository.EvidenceRepository.class), failing);
        ReflectionTestUtils.setField(svc, "bucket", "vaclaim-documents");

        assertThatThrownBy(() -> svc.downloadFromGcs("claims/1/evidence/boom"))
                .isInstanceOf(StorageException.class)
                .satisfies(ex -> assertThat(((StorageException) ex).getCode()).isEqualTo(503));
    }

    // -------------------------------------------------------------------------
    // MIME normalization: bare FILE_TYPE tokens must become real MIME types so
    // they're never served back as a bogus Content-Type like "pdf".
    // -------------------------------------------------------------------------

    @Test
    void normalizeMediaType_mapsBareTokensToMime() {
        assertThat(service.normalizeMediaType("pdf")).isEqualTo("application/pdf");
        assertThat(service.normalizeMediaType("PNG")).isEqualTo("image/png");
        assertThat(service.normalizeMediaType("jpg")).isEqualTo("image/jpeg");
        assertThat(service.normalizeMediaType("jpeg")).isEqualTo("image/jpeg");
        // Already-valid MIME types pass through.
        assertThat(service.normalizeMediaType("application/pdf")).isEqualTo("application/pdf");
        assertThat(service.normalizeMediaType("image/png")).isEqualTo("image/png");
        // Unknown / sentinel / blank → octet-stream, never a bare token.
        assertThat(service.normalizeMediaType("unknown")).isEqualTo("application/octet-stream");
        assertThat(service.normalizeMediaType("zzz")).isEqualTo("application/octet-stream");
        assertThat(service.normalizeMediaType(null)).isEqualTo("application/octet-stream");
        assertThat(service.normalizeMediaType("  ")).isEqualTo("application/octet-stream");
    }

    @Test
    void normalizeMediaType_allowlistsMimeTypes_neverTrustsActiveContent() {
        // Stored-XSS vectors: user-declared MIME must never be served as an
        // active type on the app origin.
        assertThat(service.normalizeMediaType("text/html")).isEqualTo("application/octet-stream");
        assertThat(service.normalizeMediaType("image/svg+xml")).isEqualTo("application/octet-stream");
        assertThat(service.normalizeMediaType("application/xhtml+xml")).isEqualTo("application/octet-stream");
        assertThat(service.normalizeMediaType("application/javascript")).isEqualTo("application/octet-stream");
        assertThat(service.normalizeMediaType("text/xml")).isEqualTo("application/octet-stream");
        // Parameterized types fail the exact allowlist match — safe direction.
        assertThat(service.normalizeMediaType("text/html; charset=utf-8")).isEqualTo("application/octet-stream");
        // Inert documents still pass.
        assertThat(service.normalizeMediaType("application/pdf")).isEqualTo("application/pdf");
        assertThat(service.normalizeMediaType("image/jpeg")).isEqualTo("image/jpeg");
        assertThat(service.normalizeMediaType("text/plain")).isEqualTo("text/plain");
    }
}
