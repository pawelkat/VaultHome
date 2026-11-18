import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-Kotlin port of VaultSearch `crates/vault-events` (event schema, keys, conflict rule, sync core)
// plus the Zenoh transport. JVM-only so the logic is unit-tested on the box against Rust golden vectors.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    // The API is identical in zenoh-kotlin (JVM) and zenoh-kotlin-android (same commonMain sources).
    // compileOnly here; the app supplies the Android AAR at runtime, tests use the JVM jar.
    compileOnly(libs.zenoh.kotlin.jvm)
    testImplementation(libs.zenoh.kotlin.jvm)
    testImplementation(libs.junit)
}

tasks.test {
    // Integration test against the Rust hub runs only when VAULT_EVENTS_BIN points at a vault-events binary.
    environment("VAULT_EVENTS_BIN", System.getenv("VAULT_EVENTS_BIN") ?: "")
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
