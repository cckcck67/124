plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 員工餐 App:和其他 App 分開安裝,版本號一樣由 GitHub Actions 的編譯次數決定
val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 0

android {
    namespace = "com.cck.mealplanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cck.mealplanner"
        minSdk = 26
        targetSdk = 34
        versionCode = 1 + buildNumber
        versionName = "1.0.$buildNumber"
    }
    // 和 VR 播放器用同一把簽章,新版可直接覆蓋安裝
    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/vrweb.keystore")
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
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showStackTraces = true
            }
        }
    }
}

dependencies {
    // 只用在啟動測試(不會打包進 App)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}
