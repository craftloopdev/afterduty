package com.afterduty.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * Firebase Admin SDK initialization.
 */
@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    private final Environment environment;

    public FirebaseConfig(Environment environment) {
        this.environment = environment;
    }

    // VCP-DFLT-01: fail CLOSED — secure default everywhere; dev mode is an explicit opt-in.
    @Value("${va-claim.auth.dev-mode:false}")
    private boolean devMode;

    @Value("${va-claim.firebase.project-id:}")
    private String firebaseProjectId;

    @PostConstruct
    public void init() {
        // VCP-DFLT-01: dev mode disables authentication (X-User-Email impersonation + lenient
        // Firebase init). It must NEVER be active in production. Refuse to boot if it is.
        if (devMode && environment.acceptsProfiles(Profiles.of("cloud"))) {
            throw new IllegalStateException(
                    "FATAL: va-claim.auth.dev-mode=true under the 'cloud' (production) profile. "
                    + "Dev mode disables authentication. Unset DEV_MODE (or set DEV_MODE=false) "
                    + "in the production environment.");
        }
        if (FirebaseApp.getApps().isEmpty()) {
            try {
                FirebaseOptions.Builder optionsBuilder = FirebaseOptions.builder();
                if (firebaseProjectId != null && !firebaseProjectId.isBlank()) {
                    optionsBuilder.setProjectId(firebaseProjectId);
                }
                // NOTE: if project id is blank, verifyIdToken() later throws
                // "Must initialize FirebaseApp with a project ID" — Cloud Run's
                // default credentials do not supply one. Keep va-claim.firebase.project-id set.

                // Try GOOGLE_APPLICATION_CREDENTIALS env var first
                String credPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
                if (credPath != null && !credPath.isEmpty()) {
                    optionsBuilder.setCredentials(GoogleCredentials.fromStream(new FileInputStream(credPath)));
                } else {
                    // Use Application Default Credentials (works on Cloud Run)
                    optionsBuilder.setCredentials(GoogleCredentials.getApplicationDefault());
                }

                FirebaseApp.initializeApp(optionsBuilder.build());
                log.info("Firebase Admin SDK initialized for project: {}", firebaseProjectId);
            } catch (IOException e) {
                if (devMode) {
                    log.warn("Firebase init failed (dev mode -- auth bypass active): {}", e.getMessage());
                } else {
                    log.error("Firebase init failed", e);
                    throw new RuntimeException("Firebase initialization failed", e);
                }
            }
        }
    }
}
