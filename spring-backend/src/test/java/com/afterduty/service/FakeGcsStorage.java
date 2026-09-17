package com.afterduty.service;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * In-memory fake of the GCS {@link Storage} client for tests — the
 * {@link com.afterduty.service.llm.FakeLlmAsyncProvider}-style substrate for
 * storage-truth, so the suite never needs a real bucket.
 *
 * <p>The {@link Storage} interface has ~100 methods; rather than hand-implement
 * all of them we build a Mockito mock whose single default {@code Answer}
 * dispatches by method name. The three operations {@link DocumentStorageService}
 * actually uses are backed by a real in-memory map keyed by {@code gs://bucket/path}:
 * <ul>
 *   <li>{@code create(BlobInfo, byte[], BlobTargetOption...)} — store the bytes;</li>
 *   <li>{@code readAllBytes(BlobId, BlobSourceOption...)} — return stored bytes, or
 *       throw a {@link StorageException} with HTTP code {@code 404} / reason
 *       {@code blobNotFound} when the object is absent — exactly like real GCS,
 *       which never returns null for a miss;</li>
 *   <li>{@code delete(BlobId, BlobSourceOption...)} — remove, returning whether it existed.</li>
 * </ul>
 * Any other method call throws {@link UnsupportedOperationException} so accidental
 * use of an un-faked operation fails loudly. Dispatching by name (rather than
 * per-method stubs) sidesteps Mockito's varargs-matcher fragility for the
 * zero-vararg calls {@code DocumentStorageService} makes. This gives a genuine
 * upload → download → delete round-trip in tests.
 */
public final class FakeGcsStorage {

    private FakeGcsStorage() {}

    /** Build a Mockito-backed in-memory {@link Storage}. */
    public static Storage create() {
        Map<String, byte[]> objects = new ConcurrentHashMap<>();

        return mock(Storage.class, withSettings().defaultAnswer(invocation -> {
            String name = invocation.getMethod().getName();
            Object[] args = invocation.getArguments();
            switch (name) {
                case "create" -> {
                    // create(BlobInfo, byte[], BlobTargetOption...)
                    if (args.length >= 2 && args[0] instanceof BlobInfo info && args[1] instanceof byte[] content) {
                        objects.put(key(info.getBlobId()), content);
                        return null; // returned Blob is not consumed by DocumentStorageService
                    }
                }
                case "readAllBytes" -> {
                    // readAllBytes(BlobId, BlobSourceOption...)
                    if (args.length >= 1 && args[0] instanceof BlobId id) {
                        byte[] content = objects.get(key(id));
                        if (content == null) {
                            // Honest GCS semantics: a missing blob throws a 404
                            // StorageException, it does NOT return null.
                            throw new StorageException(404, "blobNotFound",
                                    "No such object: " + key(id), null);
                        }
                        return content;
                    }
                }
                case "delete" -> {
                    // delete(BlobId, BlobSourceOption...)
                    if (args.length >= 1 && args[0] instanceof BlobId id) {
                        return objects.remove(key(id)) != null;
                    }
                }
                // Spring calls close() on the bean during context shutdown — no-op it.
                case "close" -> {
                    return null;
                }
                default -> { /* fall through to the throw below */ }
            }
            throw new UnsupportedOperationException(
                    "FakeGcsStorage does not implement Storage." + name + "(" + describe(args) + ")");
        }));
    }

    private static String key(BlobId id) {
        return "gs://" + id.getBucket() + "/" + id.getName();
    }

    private static String describe(Object[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
        }
        return sb.toString();
    }
}
