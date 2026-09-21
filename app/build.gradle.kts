plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pikafish.chess"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pikafish.chess"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // Pikafish 官方只提供 arm64 二进制；arm64 覆盖 2018 年以后几乎所有安卓机
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    // ⚠️ 关键：必须让 .so 解压到应用私有 native 目录，否则安卓不允许执行它
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // 权重文件不再二次压缩：APK 略大一点，但首次启动复制到内部存储快很多
    androidResources {
        noCompress += "nnue"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    testImplementation("junit:junit:4.13.2")
}
