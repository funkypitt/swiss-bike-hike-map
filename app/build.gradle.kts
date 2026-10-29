plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.freedomfighter.swissbikehike"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.freedomfighter.swissbikehike"
        minSdk = 28
        targetSdk = 35
        versionCode = 11
        versionName = "0.6.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // the phone is arm64; the other MapLibre native libraries would triple the APK
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }
    buildFeatures { viewBinding = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/*.md", "META-INF/LICENSE*") }
}

tasks.withType<Test> { systemProperty("transit.pack", System.getProperty("transit.pack") ?: "") }

dependencies {
    implementation(project(":brouter"))
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
