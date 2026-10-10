plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ju.pocketlocker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ju.pocketlocker"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.3.2"
    }

    // CI에서 고정 서명 키로 release APK에 서명한다 (서명이 바뀌면 업데이트 설치가 실패하므로).
    // KEYSTORE_PATH env가 없으면(로컬 빌드) debug 키로 서명한다.
    signingConfigs {
        create("pinned") {
            System.getenv("KEYSTORE_PATH")?.let { ksPath ->
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfigs.getByName("pinned")
            } else {
                signingConfigs.getByName("debug")
            }
        }
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
