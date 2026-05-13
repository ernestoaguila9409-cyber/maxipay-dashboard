import java.util.Properties

plugins {
    id("com.android.application") version "8.2.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

val secrets = Properties().apply {
    val f = rootProject.file("maxi-mobile/secrets.properties")
    if (f.exists()) load(f.inputStream())
}

fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.volt.maximobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.volt.maximobile"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.1-maxi"

        val tpn = esc(secrets.getProperty("SPIN_TPN", ""))
        val reg = esc(secrets.getProperty("SPIN_REGISTER_ID", ""))
        val key = esc(secrets.getProperty("SPIN_AUTH_KEY", ""))
        val base = esc(secrets.getProperty("SPIN_BASE_URL", "https://spinpos.net/v2"))

        buildConfigField("String", "SPIN_TPN", "\"$tpn\"")
        buildConfigField("String", "SPIN_REGISTER_ID", "\"$reg\"")
        buildConfigField("String", "SPIN_AUTH_KEY", "\"$key\"")
        buildConfigField("String", "SPIN_BASE_URL", "\"$base\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-functions-ktx")
    implementation("com.google.firebase:firebase-installations-ktx")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-process:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("com.google.android.material:material:1.11.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
}
