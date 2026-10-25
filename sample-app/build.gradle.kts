plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.vt.sampleapp"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "io.vt.sampleapp"
        minSdk = 28
        targetSdk = 35
        versionCode = 17
        versionName = "17.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
        aidl = true
    }
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.30.5"
        }
    }
}
