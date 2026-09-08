// Juan LinK — :androidApp Android 客户端（需要 Android SDK 才启用）
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.application)
}

kotlin {
    androidTarget()
    sourceSets {
        androidMain.dependencies {
            // Compose 组件版本由 compose 插件统一管理
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.foundation)
            implementation(libs.androidx.activity.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":core"))
            // 共享 UI 与业务装配（画布/工具栏/面板/连接/扫码入口）
            implementation(project(":composeUi"))
            // CameraX 扫码
            implementation(libs.camera.core)
            implementation(libs.camera.camera2)
            implementation(libs.camera.lifecycle)
            implementation(libs.camera.view)
        }
    }
}

android {
    namespace = "com.juanlink.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.juanlink.android"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("androidApp/release.jks")
            // 凭据外部化：从环境变量/gradle 属性读取，不入源码。release 构建需设：
            //   JUANLINK_STORE_PASSWORD / JUANLINK_KEY_PASSWORD（或 gradle.properties 同名属性）
            storePassword = providers.gradleProperty("JUANLINK_STORE_PASSWORD").orNull
                ?: System.getenv("JUANLINK_STORE_PASSWORD").orEmpty()
            keyAlias = "juanlink"
            keyPassword = providers.gradleProperty("JUANLINK_KEY_PASSWORD").orNull
                ?: System.getenv("JUANLINK_KEY_PASSWORD").orEmpty()
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
