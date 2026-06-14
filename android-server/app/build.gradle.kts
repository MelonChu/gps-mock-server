plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.gpsmock.server"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gpsmock.server"
        minSdk = 24          // Android 7.0 (Nougat) — 支援 Mock Location API
        targetSdk = 34       // Android 14
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 允許純文字 HTTP 流量（預設 Android 9+ 禁止）
    // 因為是區域網路使用，安全風險極低
    @Suppress("UnstableApiUsage")
    androidResources {
        noCompress.set(listOf<String>())
    }
}

dependencies {
    // AndroidX 核心（必要）
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Kotlin 序列化 — 用於 JSON 解析
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Kotlin 協程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
