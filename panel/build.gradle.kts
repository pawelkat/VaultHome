import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: the Panel note model, parser, hash, Data Layer payload codec and
// text formatting shared by :app (phone, publisher) and :wear (watch, notification + tile).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}
