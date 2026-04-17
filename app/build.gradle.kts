import java.util.Properties

plugins {
    id("com.android.application") version "8.2.0"
    id("org.jetbrains.kotlin.android") version "1.9.0"
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.evensocom.psyopvisr"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.evensocom.psyopvisr"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0-tactical"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "ROBOFLOW_API_KEY",
            "\"${localProps.getProperty("roboflow.api.key", "")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Kotlin & Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // CameraX for Pixel Camera Feed
    val camerax_version = "1.3.0-rc01"
    implementation("androidx.camera:camera-core:${camerax_version}")
    implementation("androidx.camera:camera-camera2:${camerax_version}")
    implementation("androidx.camera:camera-lifecycle:${camerax_version}")
    implementation("androidx.camera:camera-view:${camerax_version}")

    // MediaPipe for Vision/Object Detection (On-Device)
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // ML Kit on-device translation (fallback while Whisper native is not linked)
    implementation("com.google.mlkit:translate:17.0.2")

    // ML Kit on-device language identification
    implementation("com.google.mlkit:language-id:17.0.4")

    // ML Kit on-device text recognition (bundled model — no download required)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // ML Kit face detection (fallback when MediaPipe face model asset is absent)
    implementation("com.google.mlkit:face-detection:16.1.7")

    // Whisper.cpp for On-Device Speech-to-Text
    // We are simulating this via WhisperTranscriber, no external maven dependency loaded.
    implementation("com.alphacephei:vosk-android:0.3.47")

    // Lifecycle Service for background processing
    implementation("androidx.lifecycle:lifecycle-service:2.6.2")

    // ARCore
    implementation("com.google.ar:core:1.41.0")

    // TFLite for MobileCLIP
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")

    // OpenCV (used by FaceDetectionEngine identity matching path)
    implementation("org.opencv:opencv:4.9.0")
}
