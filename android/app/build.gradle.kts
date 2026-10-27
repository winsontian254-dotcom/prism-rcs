plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.prism.rcs"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.prism.rcs"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        val sourceUrl = providers.gradleProperty("prismRcsSourceUrl").getOrElse("")
        buildConfigField("String", "SOURCE_URL", "\"$sourceUrl\"")
        ndk {
            // the Go library is built for these two (see build-go.ps1)
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // libgm, bound with gomobile from ../rcsmobile
    implementation(files("libs/rcsmobile.aar"))

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.12.0")
    implementation("androidx.compose.foundation:foundation:1.12.0")
    // the launcher's font choices, through Google Play services' font provider
    implementation("androidx.compose.ui:ui-text-google-fonts:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
