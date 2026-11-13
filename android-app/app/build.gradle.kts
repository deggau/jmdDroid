plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingStoreFile = System.getenv("JMDROID_SIGNING_STORE_FILE")
val signingPassword = System.getenv("JMDROID_SIGNING_PASSWORD")

android {
    namespace = "com.jmd.android"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.jmd.android"
        minSdk = 23
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.1"
    }

    signingConfigs {
        create("release") {
            if (!signingStoreFile.isNullOrBlank()) storeFile = file(signingStoreFile)
            storePassword = signingPassword
            keyAlias = "jmdroid"
            keyPassword = signingPassword
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}
