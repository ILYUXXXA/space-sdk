plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.contextshield"
    compileSdk = 35
    buildToolsVersion = "34.0.0"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    defaultConfig {
        applicationId = "com.contextshield"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.5.2"
        // MVP is delivered to the user's current Android phone (64-bit ARM).
        // Excluding emulator/desktop ABIs keeps the debug APK small enough to distribute.
        ndk { abiFilters += setOf("arm64-v8a") }
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.32.0")
    testImplementation("junit:junit:4.13.2")
}
