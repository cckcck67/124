plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版本號由 GitHub Actions 的編譯次數決定(-PbuildNumber=...),App 依此判斷有沒有新版
val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 0

android {
    namespace = "com.cck.vrweb"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cck.vrweb"
        minSdk = 26
        targetSdk = 34
        versionCode = 100 + buildNumber
        versionName = "1.$buildNumber"
    }
    // 固定簽章:每次編譯出來的 APK 簽章相同,新版可直接安裝覆蓋舊版(不用先解除安裝)
    signingConfigs {
        getByName("debug") {
            storeFile = file("vrweb.keystore")
            storePassword = "vrwebplayer"
            keyAlias = "vrweb"
            keyPassword = "vrwebplayer"
        }
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
