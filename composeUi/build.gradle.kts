// 婵娟 JUAN Link — :composeUi 共享 UI 模块（Compose Desktop + Android 共用一套 UI）
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 平台检测：有 Android SDK 才启用 androidTarget（与 :core 一致，无 SDK 机器保持纯 JVM 构建）
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null
    || System.getenv("ANDROID_SDK_ROOT") != null
    || rootProject.file("local.properties").exists()

if (hasAndroidSdk) {
    apply(plugin = "com.android.library")
}

kotlin {
    jvm()
    if (hasAndroidSdk) androidTarget()

    sourceSets {
        val commonMain by getting
        val jvmMain by getting

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(project(":core"))
        }
        jvmMain.dependencies {
            // 提供 Skia 图像 API（decodeImageBytes / toComposeImage 的 JVM 实际）
            implementation(compose.desktop.currentOs)
        }
        if (hasAndroidSdk) {
            val androidMain by getting
            androidMain.dependencies {
                implementation(project(":core"))
            }
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
}

if (hasAndroidSdk) {
    extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
        namespace = "com.juanlink.composeui"
        compileSdk = 35
        defaultConfig {
            minSdk = 33
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }
}
