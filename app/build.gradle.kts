plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.coomi.relive"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.coomi.relive"
        minSdk = 26
        targetSdk = 34
        // 版本号可由 CI 传入（-PversionCode / -PversionName），默认从 git 时间戳派生，保证每次构建唯一递增
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull()
            ?: (System.currentTimeMillis() / 1000L).toInt()
        versionName = (project.findProperty("versionName") as String?)
            ?: "1.0-${Runtime.getRuntime().availableProcessors()}"
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.10" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
}
