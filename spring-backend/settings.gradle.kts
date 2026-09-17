plugins {
    // Auto-download JDKs (e.g. JDK 25) when not present locally; required since
    // local dev boxes typically only have one JDK installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "after-duty"
