plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kanzz.editor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kanzz.editor"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.media3:media3-exoplayer:1.9.0")
    implementation("androidx.media3:media3-ui:1.9.0")
    implementation("androidx.media3:media3-transformer:1.9.0")
    implementation("androidx.media3:media3-effect:1.9.0")
    // Existing dependency used by the current AI Motion Blur implementation.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
