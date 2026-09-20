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
        // 版本号从 git 自动派生，保证每次提交都有唯一版本，便于识别新旧。
        // 注意：Gradle Kotlin DSL 里 `java` 会被解析成 JavaPluginExtension 扩展，
        // 所以这里不能写 java.text.* / java.util.*（会 Unresolved reference），
        // 只用 java.lang 的 ProcessBuilder 与系统时间戳。
        // versionCode = git 提交数；versionName = 1.0-<提交数>-<短commit>[-<构建时间>]
        val gitCommitCount = try {
            ProcessBuilder("git", "rev-list", "--count", "HEAD")
                .redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim().toInt()
        } catch (_: Throwable) { 1 }
        val gitShort = try {
            ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                .redirectErrorStream(true).start().inputStream.bufferedReader().readText().trim()
        } catch (_: Throwable) { "dev" }
        val buildStamp = (System.currentTimeMillis() / 60000L) % 1000000L  // 分钟级，够区分构建
        versionCode = project.findProperty("versionCode")?.toString()?.toIntOrNull() ?: gitCommitCount
        versionName = project.findProperty("versionName")?.toString()
            ?: "1.0-$gitCommitCount-$gitShort-$buildStamp"
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
