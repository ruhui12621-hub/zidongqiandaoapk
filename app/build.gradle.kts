plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ruhui.hoyocookiemanager"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ruhui.hoyocookiemanager"
        minSdk = 28
        targetSdk = 35
        versionCode = 4
        versionName = "1.3.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
