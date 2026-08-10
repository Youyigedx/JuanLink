pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "juan-link"

// 平台启用检测（Windows 无 Android SDK / 非 macOS 时自动跳过对应模块）
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null
    || System.getenv("ANDROID_SDK_ROOT") != null
    || file("local.properties").exists()
val isMacHost = System.getProperty("os.name").lowercase().contains("mac")

include(":core")
include(":desktop")
include(":composeUi")
if (hasAndroidSdk) include(":androidApp")
if (isMacHost) include(":iosApp")

logger.lifecycle("JUAN Link: Android enabled=$hasAndroidSdk, iOS enabled=$isMacHost")
