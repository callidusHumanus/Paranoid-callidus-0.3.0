plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ai.callidus.paranoid"
    compileSdk = 34
    defaultConfig {
        applicationId = "ai.callidus.paranoid"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.3.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildTypes {
        release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("info.guardianproject:tor-android:0.4.7.14")
}
