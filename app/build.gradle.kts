plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jun8222.batteryhub"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jun8222.batteryhub"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // 개인 설치용 고정 서명키 (업데이트 시 덮어쓰기 설치가 되도록 고정)
    signingConfigs {
        create("fixed") {
            storeFile = file("batteryhub.jks")
            storePassword = "batteryhub"
            keyAlias = "batteryhub"
            keyPassword = "batteryhub"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
