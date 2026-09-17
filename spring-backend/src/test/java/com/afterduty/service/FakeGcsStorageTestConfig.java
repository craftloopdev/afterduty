package com.afterduty.service;

import com.google.cloud.storage.Storage;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Supplies an in-memory {@link Storage} bean (see {@link FakeGcsStorage}) that
 * overrides {@code GcsConfig.gcsStorage()} in tests, so the storage-truth upload
 * path runs against a fake bucket instead of real GCS. Imported by tests that
 * exercise file uploads end-to-end.
 */
@TestConfiguration
public class FakeGcsStorageTestConfig {

    @Bean
    @Primary
    public Storage fakeGcsStorage() {
        return FakeGcsStorage.create();
    }
}
