plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pikmin.standalone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pikmin.standalone"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        disable += "MockLocation"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.health.connect:connect-client:1.1.0")
}
