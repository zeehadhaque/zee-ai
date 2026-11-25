import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}

val rawStoreFile = keystoreProps.getProperty("storeFile") ?: ""
val resolvedStoreFile = if (file(rawStoreFile).exists()) {
    file(rawStoreFile)
} else {
    rootProject.file(rawStoreFile)
}

val hasReleaseKeystore = keystorePropsFile.exists() &&
    rawStoreFile.isNotBlank() &&
    resolvedStoreFile.exists()

android {
    namespace = "com.zeeai.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zeeai.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        // Modern 64-bit phone (Xiaomi-shoho) er jonno APK size halka rakhte
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = resolvedStoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            // MediaPipe JNI ও Compose reflection 100% crash-free রাখতে minify false রাখা হলো
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        getByName("debug") {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Coroutines & Networking (Gemini SSE + YouTube/Social REST APIs + Model Downloads)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Room Database (Offline Long-Term Memory, Chat History & Draft Queue)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Encrypted Local Key Storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Native Google OAuth 2.0 AuthorizationClient (YouTube Data & Analytics APIs)
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // On-Device Offline LLM Inference (Downloaded dynamically — zero APK bloat)
    implementation("com.google.mediapipe:tasks-genai:0.10.27")

    // Unit Tests
    testImplementation("junit:junit:4.13.2")
}
