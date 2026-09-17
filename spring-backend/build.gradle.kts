plugins {
    java
  	id("org.springframework.boot") version "4.0.4"
  	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.afterduty"
version = "4.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}


dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Gemini via Google Vertex AI Java SDK (direct, not Spring AI)
    implementation("com.google.cloud:google-cloud-vertexai:1.15.0")

    // Claude via Anthropic SDK on Vertex AI. 2.35+ is required for the `us`
    // multi-region endpoint (VertexBackend maps region "us" to
    // aiplatform.us.rep.googleapis.com; 2.18 only knew global + regional hosts).
    implementation("com.anthropic:anthropic-java:2.62.0")
    implementation("com.anthropic:anthropic-java-vertex:2.62.0")

    // GCS document storage
    implementation("com.google.cloud:google-cloud-storage:2.73.0")

    // Firebase Admin SDK
    implementation("com.google.firebase:firebase-admin:9.10.0")

    // Database
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.google.cloud:spring-cloud-gcp-starter-sql-postgresql:6.3.0")

    // Jackson
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // Stripe (subscription billing — test mode)
    implementation("com.stripe:stripe-java:33.4.2")

    // SendGrid (passwordless email-code OTP delivery — §5 of passwordless-otp-auth-spec)
    implementation("com.sendgrid:sendgrid-java:4.10.3")

    // WebAuthn / passkeys (auth program P1.3). The Yubico server-side library does
    // the attestation + assertion verification (challenge binding, origin/rpId match,
    // signature, sign_count monotonicity, UV policy) — we never hand-roll COSE/CBOR/sig.
    implementation("com.yubico:webauthn-server-core:2.9.0")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // CBOR for the in-process SoftwareAuthenticator test helper (builds attestation
    // objects + COSE keys the Yubico verifier consumes). This is a runtime-scoped
    // transitive of webauthn-server-core; we depend on it explicitly for the test
    // compile classpath. Version pinned to the resolved transitive.
    testImplementation("com.upokecenter:cbor:4.5.6")
}

tasks.withType<Test> {
    // Many Spring contexts across 75 suites overflow Gradle's 512MB executor
    // default and crash a worker mid-run (observed flake); 1g is comfortable.
    maxHeapSize = "1g"
    useJUnitPlatform {
        // Run only @Tag("regression") tests when `-PregressionOnly` is passed.
        // See /regression/MANIFEST.md for the pre-prod regression workflow.
        if (project.hasProperty("regressionOnly")) {
            includeTags("regression")
        }
        // @Tag("livesmoke") tests hit a real Vertex endpoint (cost + ADC). They are excluded from
        // the default suite and only run when explicitly opted in with `-PliveSmoke`.
        if (!project.hasProperty("liveSmoke")) {
            excludeTags("livesmoke")
        }
        // @Tag("eval-live") tests are the Increment 8 SCORED live eval — real Vertex Claude +
        // Gemini + a Gemini judge (cost + ADC). Excluded from the default suite; opt in with
        // `-PliveEval`. The offline gate (@Tag("eval-offline")) stays in the default suite.
        if (!project.hasProperty("liveEval")) {
            excludeTags("eval-live")
        }
    }
    // Forward the eval harness' opt-in system properties (case subset + snapshot
    // write controls) from the gradle invocation into the forked test JVM, so
    // `-Peval.cases=` / the evalSnapshot task reach OfflineGoldenPipelineTest.
    listOf("eval.cases", "eval.snapshot.write", "eval.snapshot.dir", "eval.run.id",
            "eval.report.dir", "eval.git.sha", "eval.baseline",
            "eval.runs.dir", "eval.waiver").forEach { key ->
        (project.findProperty(key) as String?)?.let { systemProperty(key, it) }
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}

// Increment 8 eval harness — convenience tasks (spec §2.6).

// Short git sha stamped into the live-eval report (report.json `git_sha`), so a
// committed run directory records exactly which tree produced it. Falls back to
// "unknown" outside a git checkout — the report still writes.
fun gitShortSha(): String = try {
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
    }.standardOutput.asText.get().trim()
} catch (e: Exception) {
    "unknown"
}

// Just the offline eval suite (default `check` already runs it; this is for fast iteration).
tasks.register<Test>("evalOffline") {
    group = "verification"
    description = "Run only the Increment 8 OFFLINE golden-pipeline eval suite."
    useJUnitPlatform { includeTags("eval-offline") }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
}

// The SCORED live eval against real Vertex lanes + Gemini judge. Requires -PliveEval.
tasks.register<Test>("evalLive") {
    group = "verification"
    description = "Run the Increment 8 LIVE scored eval (real models + judge). Requires -PliveEval and ADC."
    useJUnitPlatform { includeTags("eval-live") }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("eval.report.dir", "${rootDir}/../docs/qa/evals/runs")
    systemProperty("eval.git.sha", gitShortSha())
    (project.findProperty("eval.baseline") as String?)?.let { systemProperty("eval.baseline", it) }
    doFirst {
        require(project.hasProperty("liveEval")) {
            "evalLive must be invoked with -PliveEval (it hits real Vertex endpoints — cost + ADC)."
        }
    }
}

// Regenerate the offline snapshot. Requires naming a live-eval run id — that's the
// acknowledgment that the scored tier was run (or consciously waived) for this change.
tasks.register<Test>("evalSnapshot") {
    group = "verification"
    description = "Regenerate golden/snapshots/offline-summary.json. Requires -PevalRunId=<live-run-id>."
    useJUnitPlatform { includeTags("eval-offline") }
    filter { includeTestsMatching("com.afterduty.eval.OfflineGoldenPipelineTest") }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("eval.snapshot.write", "true")
    systemProperty("eval.snapshot.dir", "${projectDir}/src/test/resources/golden/snapshots")
    systemProperty("eval.run.id", (project.findProperty("evalRunId") as String?) ?: "UNSET")
    // Where committed live-run report.json files live; forwarded so the snapshot write
    // path can technically verify the named run actually exists + its prompt_versions
    // match the about-to-be-written constants (review fix minor #3).
    systemProperty("eval.runs.dir", "${rootDir}/../docs/qa/evals/runs")
    (project.findProperty("evalWaiver") as String?)?.let { systemProperty("eval.waiver", it) }
    outputs.upToDateWhen { false }
    doFirst {
        require(project.hasProperty("evalRunId")) {
            "evalSnapshot must be invoked with -PevalRunId=<live-eval-run-id> — naming the run is the " +
            "acknowledgment that the scored live tier was run (or consciously waived) for this change."
        }
        // Build-ENFORCED link (was honor-system only — review fix minor #3): the named
        // run must have a committed report.json, OR the operator must pass an explicit
        // -PevalWaiver="<reason>" so the conscious-waiver path (spec §2.5) is a logged,
        // reviewable choice rather than a silent offline bypass.
        val runId = project.findProperty("evalRunId") as String
        val reportJson = rootDir.resolve("../docs/qa/evals/runs/$runId/report.json").normalize()
        val hasWaiver = project.hasProperty("evalWaiver")
        require(reportJson.exists() || hasWaiver) {
            "evalSnapshot: no committed live run at docs/qa/evals/runs/$runId/report.json. " +
            "Either name a real committed run id, or pass -PevalWaiver=\"<reason>\" to consciously " +
            "waive the live-run linkage for this change (the reason is recorded in the invocation)."
        }
        if (hasWaiver) {
            logger.warn("evalSnapshot: WAIVING live-run linkage for run id '$runId' — reason: " +
                    "${project.findProperty("evalWaiver")}. The snapshot's eval_run_id will not " +
                    "correspond to a committed scored run.")
        }
    }
}
