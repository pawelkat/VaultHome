import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.vaulthome.wear"
    // Tiles 1.6.x needs compileSdk >= 35; 36 matches SwimWeek's :wear.
    compileSdk = 36

    defaultConfig {
        // Must equal the phone app's applicationId so the Wearable Data Layer pairs them.
        // No debug suffix: :app has none, and the installed phone app is com.vaulthome.app.
        applicationId = "com.vaulthome.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Galaxy Watch 7 (SM-L310) runs a 32-bit userspace: CPU ABI armeabi-v7a.
            // The module currently ships no native code; keep both ABIs if any library adds some.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        debug {
            // Small sideload APK: R8 + resource shrinking on the debug build too.
            // Still debug-signed with the builder's debug keystore (same cert as :app).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":panel"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.protolayout.expression)
    implementation(libs.androidx.concurrent.futures.ktx)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
}
