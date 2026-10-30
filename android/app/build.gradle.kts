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
        // Prism Launcher carries a build of this app and installs or updates it when its version
        // is newer than the one on the phone: raise versionCode with every build handed to Prism
        versionCode = 2
        versionName = "0.2.0"
        val sourceUrl = providers.gradleProperty("prismRcsSourceUrl").getOrElse("")
        buildConfigField("String", "SOURCE_URL", "\"$sourceUrl\"")
        ndk {
            // the Go library is built for these two (see build-go.ps1)
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        // The build Prism Launcher carries (bundle-into-prism.ps1). Signed with this machine's debug
        // key for now: every build handed out must use the same key, or it can't update itself.
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // the Go library is most of the app: keep it compressed in the APK
    packaging {
        jniLibs.useLegacyPackaging = true
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
