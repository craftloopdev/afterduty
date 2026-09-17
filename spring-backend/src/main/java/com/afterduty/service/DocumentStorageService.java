package com.afterduty.service;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.afterduty.model.EvidenceItem;
import com.afterduty.repository.EvidenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Handles document storage and duplicate detection.
 *
 * - Computes SHA-256 hash of raw file bytes
 * - Checks for duplicate uploads within the same claim
 * - Stores files to GCS
 * - Builds storage paths: {user_id}/{claim_id}/{evidence_id}.{ext}
 */
@Service
public class DocumentStorageService {

    private static final Logger log = LoggerFactory.getLogger(DocumentStorageService.class);

    private final EvidenceRepository evidenceRepository;
    private final Storage gcsStorage;

    @Value("${va-claim.gcs.bucket:vaclaim-documents}")
    private String bucket;

    public DocumentStorageService(EvidenceRepository evidenceRepository, Storage gcsStorage) {
        this.evidenceRepository = evidenceRepository;
        this.gcsStorage = gcsStorage;
    }

    /**
     * Upload file bytes to GCS at the given storage path. The object's
     * {@code Content-Type} is guessed from the path extension.
     * Full path: gs://{bucket}/{storagePath}
     */
    public void uploadToGcs(byte[] fileBytes, String storagePath) {
        uploadToGcs(fileBytes, storagePath, guessContentType(storagePath));
    }

    /**
     * Upload file bytes to GCS at the given storage path with an explicit
     * {@code Content-Type} on the object metadata. Use this for content-addressed
     * uploads where the path carries no extension — the caller supplies the
     * media type from the original upload so downloads/viewers serve the correct
     * type. A null/blank contentType falls back to the path-extension guess.
     * Full path: gs://{bucket}/{storagePath}
     */
    public void uploadToGcs(byte[] fileBytes, String storagePath, String contentType) {
        String ct = (contentType != null && !contentType.isBlank())
                ? contentType
                : guessContentType(storagePath);
        BlobId blobId = BlobId.of(bucket, storagePath);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType(ct)
                .build();
        gcsStorage.create(blobInfo, fileBytes);
        log.info("Uploaded {} bytes to gs://{}/{} ({})", fileBytes.length, bucket, storagePath, ct);
    }

    /**
     * Build a content-addressed GCS object path for a piece of uploaded evidence.
     * Format: {@code claims/{claimId}/evidence/{fileHash}} — keyed by the SHA-256
     * of the bytes so the same file re-uploaded resolves to the same object and
     * dedupes naturally (and a re-upload after a deletion just re-writes the same
     * key). The original filename and media type live on the DB row, not the path.
     */
    public String buildEvidenceObjectPath(Long claimId, String fileHash) {
        return String.format("claims/%d/evidence/%s", claimId, fileHash);
    }

    /**
     * Download file bytes from GCS.
     *
     * <p>Returns {@code null} when the object does not exist — the documented
     * "miss" contract every reader relies on. Real GCS does NOT return null for a
     * missing blob: {@link Storage#readAllBytes} throws a {@link StorageException}
     * with HTTP code {@code 404} (reason {@code notFound}/{@code blobNotFound}).
     * We translate that single not-found case into the null miss, log a warning,
     * and rethrow every other {@link StorageException} (auth, network, 5xx, etc.)
     * so genuine failures are never silently swallowed as "not found".
     */
    public byte[] downloadFromGcs(String storagePath) {
        BlobId blobId = BlobId.of(bucket, storagePath);
        byte[] content;
        try {
            content = gcsStorage.readAllBytes(blobId);
        } catch (StorageException e) {
            if (isNotFound(e)) {
                log.warn("GCS object not found: gs://{}/{}", bucket, storagePath);
                return null;
            }
            throw e;
        }
        if (content == null) {
            // Defensive: a fake/alternate client may still signal a miss with null.
            log.warn("GCS object not found: gs://{}/{}", bucket, storagePath);
            return null;
        }
        log.info("Downloaded {} bytes from gs://{}/{}", content.length, bucket, storagePath);
        return content;
    }

    /**
     * Whether a {@link StorageException} represents a missing object (HTTP 404 /
     * reason {@code notFound} or {@code blobNotFound}) rather than a transport or
     * authorization failure.
     */
    private static boolean isNotFound(StorageException e) {
        if (e.getCode() == 404) {
            return true;
        }
        String reason = e.getReason();
        return "notFound".equals(reason) || "blobNotFound".equals(reason);
    }

    /**
     * Delete a file from GCS.
     * Returns true if the object was deleted, false if it did not exist.
     */
    public boolean deleteFromGcs(String storagePath) {
        BlobId blobId = BlobId.of(bucket, storagePath);
        boolean deleted = gcsStorage.delete(blobId);
        if (deleted) {
            log.info("Deleted gs://{}/{}", bucket, storagePath);
        } else {
            log.warn("GCS object not found for deletion: gs://{}/{}", bucket, storagePath);
        }
        return deleted;
    }

    /**
     * Compute SHA-256 hash of file bytes.
     */
    public String computeHash(byte[] fileBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(fileBytes);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Check if a document with the same hash already exists in this claim.
     * Returns true if this is a duplicate.
     */
    public boolean isDuplicate(Long claimId, String fileHash) {
        return evidenceRepository.existsByClaimIdAndFileHash(claimId, fileHash);
    }

    /**
     * Build the GCS storage path for a document.
     * Format: {user_id}/{claim_id}/{evidence_id}.{extension}
     *
     * No filename in the path -- original filename is stored in the DB only.
     */
    public String buildStoragePath(Long userId, Long claimId, Long evidenceId, String filename) {
        String extension = extractExtension(filename);
        return String.format("%d/%d/%d.%s", userId, claimId, evidenceId, extension);
    }

    /**
     * Extract file extension from filename, defaulting to "bin" if none.
     */
    private String extractExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "bin";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    /**
     * Guess content type from storage path extension.
     */
    private String guessContentType(String storagePath) {
        String lower = storagePath.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return "application/octet-stream";
    }

    /**
     * Decode a base64-encoded upload payload.
     * Expected format:
     *   CONTENT_ENCODING: base64
     *   FILE_TYPE: pdf
     *   FILENAME: document.pdf
     *
     *   <base64 data>
     *
     * Returns the raw decoded bytes, or null if not base64 encoded.
     */
    public byte[] decodeUploadContent(String content) {
        if (content == null || !content.startsWith("CONTENT_ENCODING: base64")) {
            return null;
        }
        String[] parts = content.split("\n\n", 2);
        if (parts.length < 2) {
            return null;
        }
        try {
            return java.util.Base64.getDecoder().decode(parts[1].strip());
        } catch (IllegalArgumentException e) {
            log.warn("Failed to decode base64 content: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse the FILE_TYPE from the upload header.
     *
     * <p>Note: the FILE_TYPE header can be a bare token like {@code pdf} or {@code png}
     * (not a real MIME type). Callers that persist this as the row's {@code mediaType}
     * (and therefore serve it back as a {@code Content-Type}) must run it through
     * {@link #normalizeMediaType(String)} first.
     */
    public String parseFileType(String content) {
        if (content == null) return "unknown";
        for (String line : content.split("\n")) {
            if (line.startsWith("FILE_TYPE:")) {
                return line.split(":", 2)[1].strip().toLowerCase();
            }
        }
        return "unknown";
    }

    /**
     * MIME types we are willing to store and later serve as a Content-Type.
     * Anything outside this set — notably text/html, image/svg+xml,
     * application/xhtml+xml, anything script-ish — becomes
     * application/octet-stream: a user-controlled MIME served on the app
     * origin is a stored-XSS vector, so only inert types pass through.
     */
    private static final java.util.Set<String> SERVABLE_MEDIA_TYPES = java.util.Set.of(
            "application/pdf",
            "image/png", "image/jpeg", "image/gif", "image/webp",
            "image/heic", "image/heif", "image/tiff", "image/bmp",
            "text/plain", "text/csv", "application/json",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/octet-stream");

    /**
     * Normalize an upload-declared file type into a MIME type SAFE to store as
     * the row's {@code mediaType} and serve as a {@code Content-Type}.
     *
     * <p>Accepts an already-valid MIME type (allowlisted — never trusted) or a
     * bare token from the {@code FILE_TYPE} header ({@code pdf}, {@code png},
     * {@code jpg}, …) mapped to its canonical MIME type. Anything unrecognized
     * or outside {@link #SERVABLE_MEDIA_TYPES} — including the {@code "unknown"}
     * sentinel — becomes {@code application/octet-stream}.
     */
    public String normalizeMediaType(String fileType) {
        if (fileType == null || fileType.isBlank()) {
            return "application/octet-stream";
        }
        String t = fileType.strip().toLowerCase();
        // Already a MIME type (has a subtype): allowlist, never trust.
        // Parameters (e.g. "; charset=...") fail the exact match and degrade
        // to octet-stream, which is the safe direction.
        if (t.contains("/")) {
            return SERVABLE_MEDIA_TYPES.contains(t) ? t : "application/octet-stream";
        }
        return switch (t) {
            case "pdf"          -> "application/pdf";
            case "png"          -> "image/png";
            case "jpg", "jpeg"  -> "image/jpeg";
            case "gif"          -> "image/gif";
            case "webp"         -> "image/webp";
            case "heic"         -> "image/heic";
            case "heif"         -> "image/heif";
            case "tif", "tiff"  -> "image/tiff";
            case "bmp"          -> "image/bmp";
            case "txt", "text"  -> "text/plain";
            case "csv"          -> "text/csv";
            case "json"         -> "application/json";
            case "doc"          -> "application/msword";
            case "docx"         -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls"          -> "application/vnd.ms-excel";
            case "xlsx"         -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default             -> "application/octet-stream";
        };
    }

    /**
     * Parse the FILENAME from the upload header.
     */
    public String parseFilename(String content) {
        if (content == null) return null;
        for (String line : content.split("\n")) {
            if (line.startsWith("FILENAME:")) {
                return line.split(":", 2)[1].strip();
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Storage-truth accessors — the single entry points every reader uses to turn
    // an EvidenceItem back into file bytes / extraction text, regardless of where
    // the bytes physically live.
    // -------------------------------------------------------------------------

    /**
     * Load the raw file bytes for a piece of evidence.
     *
     * <p>Resolution order:
     * <ol>
     *   <li><b>GCS-backed rows</b> (the current upload path): {@code gcs_path} is
     *       set → download the object from GCS.</li>
     *   <li><b>Legacy rows</b> (uploaded before storage-truth, no {@code gcs_path}):
     *       the file bytes live inside {@code raw_content} as a base64 envelope
     *       ({@code CONTENT_ENCODING: base64 …}) → decode it. If {@code raw_content}
     *       is not an envelope (plain text / quick-add / typed evidence), its UTF-8
     *       bytes are returned as-is.</li>
     * </ol>
     *
     * @return the file bytes, or {@code null} if the row carries neither a
     *         {@code gcs_path} nor {@code raw_content}.
     */
    public byte[] loadFileBytes(EvidenceItem evidence) {
        if (evidence == null) {
            return null;
        }
        if (evidence.getGcsPath() != null) {
            return downloadFromGcs(evidence.getGcsPath());
        }
        // Legacy-rows path: file bytes (or text) still live in raw_content.
        String raw = evidence.getRawContent();
        if (raw == null) {
            return null;
        }
        byte[] decoded = decodeUploadContent(raw);
        if (decoded != null) {
            return decoded;
        }
        return raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Return the text the extraction pipeline feeds to the LLM for a piece of
     * evidence. This intentionally preserves the <em>exact</em> string shape the
     * pipeline saw before storage-truth, so extraction behaves identically:
     *
     * <ul>
     *   <li><b>GCS-backed file rows</b>: reconstruct the canonical base64 envelope
     *       ({@code CONTENT_ENCODING: base64\nFILE_TYPE: …\nFILENAME: …\n\n<base64>})
     *       from the downloaded bytes + media type + filename — byte-for-byte what
     *       {@code IntakeController} used to persist into {@code raw_content}.</li>
     *   <li><b>Legacy rows and text/quick-add evidence</b>: return
     *       {@code raw_content} verbatim (it already holds either the legacy
     *       base64 envelope or plain typed text).</li>
     * </ul>
     */
    public String loadExtractionText(EvidenceItem evidence) {
        if (evidence == null) {
            return "";
        }
        if (evidence.getGcsPath() != null) {
            byte[] bytes = downloadFromGcs(evidence.getGcsPath());
            if (bytes == null) {
                return "";
            }
            String fileType = evidence.getMediaType() != null
                    ? evidence.getMediaType()
                    : "application/octet-stream";
            String filename = evidence.getFilename() != null
                    ? evidence.getFilename()
                    : "upload";
            return "CONTENT_ENCODING: base64\nFILE_TYPE: " + fileType
                    + "\nFILENAME: " + filename
                    + "\n\n" + Base64.getEncoder().encodeToString(bytes);
        }
        // Legacy rows + text/quick-add evidence: raw_content is already the text.
        return evidence.getRawContent() != null ? evidence.getRawContent() : "";
    }
}
