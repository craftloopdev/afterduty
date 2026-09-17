package com.afterduty.config;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Google Cloud Storage configuration.
 * Uses Application Default Credentials (ADC) for authentication.
 */
@Configuration
public class GcsConfig {

    private static final Logger log = LoggerFactory.getLogger(GcsConfig.class);

    @Value("${va-claim.gcs.bucket:vaclaim-documents}")
    private String bucket;

    @Value("${va-claim.vertex.project-id:}")
    private String projectId;

    @Bean
    public Storage gcsStorage() {
        log.info("Configuring GCS Storage client (project={}, bucket={})", projectId, bucket);
        StorageOptions.Builder builder = StorageOptions.newBuilder();
        if (projectId != null && !projectId.isBlank()) {
            // setProjectId throws on empty input; skipping lets the SDK
            // derive from ADC / metadata server when running on GCP.
            builder.setProjectId(projectId);
        }
        return builder.build().getService();
    }
}
