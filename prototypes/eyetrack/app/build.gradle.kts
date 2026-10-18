plugins {
    id("com.android.application")
}

android {
    namespace = "edu.uw.ubicomp.apollo.eyetrack"
    compileSdk = 36

    defaultConfig {
        applicationId = "edu.uw.ubicomp.apollo.eyetrack"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += "task" // MediaPipe memory-maps the model, so keep it uncompressed in the APK
    }
}

dependencies {
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")
    implementation("com.google.mediapipe:tasks-vision:1.0.0")
}
