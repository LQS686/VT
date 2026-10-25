plugins {
    id("com.android.library")
}

android {
    namespace = "io.vt.xposed"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20")
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.30.5"
        }
    }

    buildFeatures {
        prefab = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("org.lsposed.libcxx:libcxx-full:30.0.16248370")
    implementation("io.github.vvb2060.ndk:dobby:1.2")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.apache.commons:commons-lang3:3.17.0")
}
