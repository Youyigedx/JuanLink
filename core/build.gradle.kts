// 婵娟 JUAN Link — :core 纯 KMP 业务内核（无 UI）
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// 平台检测：有 Android SDK 才启用 androidTarget（无 SDK 的机器保持纯 JVM 构建）
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null
    || System.getenv("ANDROID_SDK_ROOT") != null
    || rootProject.file("local.properties").exists()

// Android 库插件必须早于 kotlin{} 应用（androidTarget() 依赖它）；用 apply() 支持条件加载
if (hasAndroidSdk) {
    apply(plugin = "com.android.library")
}

kotlin {
    jvm()
    if (hasAndroidSdk) androidTarget()

    sourceSets {
        val commonMain by getting
        val jvmMain by getting

        // jvm 与 android 共享的中间层：TcpTransport 用 java.net，两端同为 JVM 字节码，单份维护
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain)
        }
        jvmMain.dependsOn(jvmAndAndroidMain)

        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.cbor)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation(libs.zxing.core)
        }

        if (hasAndroidSdk) {
            val androidMain by getting
            androidMain.dependsOn(jvmAndAndroidMain)
            androidMain.dependencies {
                implementation(libs.zxing.core)          // 二维码解码（与 JVM 相同）
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
    // 用扩展名方式访问，避免在无 Android 插件时顶层 `android` 引用导致脚本编译失败
    extensions.configure<com.android.build.gradle.LibraryExtension>("android") {
        namespace = "com.juanlink.core"
        compileSdk = 35
        defaultConfig {
            // X25519（java.security.interfaces.XEC*）需 API 33+，系统原生支持 XDH
            minSdk = 33
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }
}
