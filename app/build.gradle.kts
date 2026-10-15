plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.legendarymsr.lfsdroid"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.legendarymsr.lfsdroid"
        minSdk = 29
        targetSdk = 37
        versionCode = 3
        versionName = "0.1.2"
        ndk { abiFilters += "arm64-v8a" }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    testImplementation(libs.junit)
}
