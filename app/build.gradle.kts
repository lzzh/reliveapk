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
        // 版本号从 git 自动派生，保证每次提交/构建版本号唯一递增，便于识别新旧
        // versionCode = git 提交数；versionName = 1.0-日期-短commit
        val gitCommitCount = try {
            ProcessBuilder("git", "rev-list", "--count", "HEAD")
                .redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim().toInt()
        } catch (_: Throwable) { 1 }
        val gitShort = try {
            ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                .redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim()
        } catch (_: Throwable) { "dev" }
        versionCode = project.findProperty("versionCode")?.toString()?.toIntOrNull() ?: gitCommitCount
        versionName = project.findProperty("versionName")?.toString()
            ?: "1.0-${java.text.SimpleDateFormat("yyMMddHHmm").format(java.util.Date())}-$gitShort"
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
