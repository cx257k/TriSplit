plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "jp.trisplit"
    compileSdk = 37

    defaultConfig {
        applicationId = "jp.trisplit"
        minSdk = 31
        targetSdk = 36
        // Bump on every AIDL change: Shizuku reuses a running user service with the same version.
        versionCode = 70
        versionName = "0.35.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // values/ is English, then Japanese and Simplified and Traditional Chinese. The generated
        // LocaleConfig lists them in the system's per-app language settings (Android 13+).
        localeFilters += listOf("en", "ja", "b+zh+Hans", "b+zh+Hant")
        generateLocaleConfig = true
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)

    testImplementation(libs.junit)
}
