// Juan LinK — :iosApp iOS 壳（仅 macOS 主机启用）
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "JuanLinkApp"
            isStatic = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(compose.material3)
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":core"))
        }
    }
}
