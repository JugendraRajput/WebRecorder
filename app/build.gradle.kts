plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.jdpublication.webrecorder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jdpublication.webrecorder"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)

    implementation(libs.poi)
    implementation(libs.poi.ooxml)
    implementation(libs.localbroadcastmanager)
}