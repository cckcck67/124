plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cck.vrweb"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cck.vrweb"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.5"
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
